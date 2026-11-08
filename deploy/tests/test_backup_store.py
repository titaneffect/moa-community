"""Offline safety checks: never connect to EC2, Docker, or S3."""
import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    'backup_store', Path(__file__).parents[1] / 'scripts' / 'backup-store.py')
store = importlib.util.module_from_spec(spec)
spec.loader.exec_module(store)


class RetentionTests(unittest.TestCase):
    def setUp(self):
        (Path(__file__).parents[2] / 'tmp').mkdir(exist_ok=True)
        versioning = patch.object(store, 'require_unversioned_bucket')
        versioning.start()
        self.addCleanup(versioning.stop)
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def dump_listing(self, count):
        return {'db-backups/scott-202611%02d-160001.%s' % (day, ext): 100
                for day in range(1, count + 1) for ext in ('dmp', 'log')}

    def test_dump_preview_never_deletes_and_counts_pairs_not_files(self):
        with patch.object(store, 'objects', return_value=self.dump_listing(8)), \
                patch.object(store, 'read_object', return_value='Job SCOTT successfully completed'), \
                patch.object(store, 'remove_object') as remove:
            store.prune_dumps()
            remove.assert_not_called()

    def test_dump_apply_deletes_only_oldest_pair_and_ignores_nested_rman(self):
        listing = self.dump_listing(8)
        listing['db-backups/rman/FREE/scott-20260101-000000.log'] = 100
        with patch.object(store, 'objects', return_value=listing), \
                patch.object(store, 'read_object', return_value='Job SCOTT successfully completed'), \
                patch.object(store, 'remove_object') as remove:
            store.prune_dumps(True)
            self.assertEqual([call.args[0] for call in remove.call_args_list], [
                'db-backups/scott-20261101-160001.dmp',
                'db-backups/scott-20261101-160001.log'])

    def test_failed_or_incomplete_dump_does_not_evict_a_good_pair(self):
        listing = self.dump_listing(9)
        del listing['db-backups/scott-20261109-160001.dmp']
        def log(key):
            return ('Job SCOTT stopped due to fatal error' if '20261108' in key
                    else 'Job SCOTT successfully completed')
        with patch.object(store, 'objects', return_value=listing), \
                patch.object(store, 'read_object', side_effect=log), \
                patch.object(store, 'remove_object') as remove:
            store.prune_dumps(True)
            remove.assert_not_called()

    def test_dump_read_failure_aborts_before_any_deletion(self):
        with patch.object(store, 'objects', return_value=self.dump_listing(9)), \
                patch.object(store, 'read_object', side_effect=[
                    'Job SCOTT successfully completed', RuntimeError('S3 unavailable')]), \
                patch.object(store, 'remove_object') as remove:
            with self.assertRaises(RuntimeError):
                store.prune_dumps(True)
            remove.assert_not_called()

    def manifest(self, day, dbid='1509902213'):
        return {'stamp': '202611%02d-000000' % day, 'dbid': dbid,
                'kind': 'full-plus-archivelog',
                'files': [{'name': 'FREE_piece.bkp', 'size': 100, 'sha256': 'a' * 64}]}

    def rman_patches(self, root, manifests):
        stack = contextlib.ExitStack()
        self.addCleanup(stack.close)
        listing = {store.RMAN + item['stamp'] + '/_SUCCESS.json': 100 for item in manifests}
        records = {key: json.dumps(item) for key, item in zip(listing, manifests)}
        stack.enter_context(patch.object(store, 'objects', return_value=listing))
        stack.enter_context(patch.object(store, 'read_object', side_effect=records.__getitem__))
        stack.enter_context(patch.object(store, 'managed_root', return_value=Path(root)))
        verify = stack.enter_context(patch.object(store, 'verify_remote'))
        remove = stack.enter_context(patch.object(store, 'remove_object'))
        run = stack.enter_context(patch.object(store, 'run', return_value='Recovery Manager complete.'))
        return verify, remove, run

    def test_rman_preview_never_calls_delete_or_docker(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            verify, remove, run = self.rman_patches(root, [self.manifest(i) for i in (1, 8, 15)])
            store.prune_rman()
            self.assertEqual(verify.call_count, 2)
            remove.assert_not_called()
            run.assert_not_called()

    def test_rman_missing_retained_object_prevents_all_deletion(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            verify, remove, run = self.rman_patches(root, [self.manifest(i) for i in (1, 8, 15)])
            verify.side_effect = RuntimeError('retained object missing')
            with self.assertRaises(RuntimeError):
                store.prune_rman(True)
            remove.assert_not_called()
            run.assert_not_called()

    def test_rman_failed_upload_blocks_pruning(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            directory = Path(root) / '20261116-000000'
            directory.mkdir()
            (directory / 'unpublished.bkp').write_bytes(b'partial')
            _, remove, run = self.rman_patches(root, [self.manifest(i) for i in (1, 8, 15)])
            with self.assertRaisesRegex(RuntimeError, '미완료'):
                store.prune_rman(True)
            remove.assert_not_called()
            run.assert_not_called()

    def test_rman_delete_failure_keeps_remote_backup(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            _, remove, run = self.rman_patches(root, [self.manifest(i) for i in (1, 8, 15)])
            run.return_value = 'RMAN-03009: deletion failed'
            with self.assertRaises(RuntimeError):
                store.prune_rman(True)
            remove.assert_not_called()

    def test_rman_prunes_one_bundle_and_removes_marker_last(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            _, remove, run = self.rman_patches(root, [self.manifest(i) for i in (1, 8, 15)])
            store.prune_rman(True)
            commands = run.call_args.kwargs['input'].decode()
            self.assertIn('/20261101-000000/FREE_piece.bkp', commands)
            self.assertNotIn('/20261108-', commands)
            self.assertEqual([call.args[0] for call in remove.call_args_list], [
                store.RMAN + '20261101-000000/FREE_piece.bkp',
                store.RMAN + '20261101-000000/_SUCCESS.json'])
            # Log-only directories left by an earlier cleanup must not block later runs.
            directory = Path(root) / '20261001-000000'
            directory.mkdir()
            (directory / 'diagnostic.log').write_text('retained')
            store.prune_rman()

    def test_manifest_rejects_path_traversal_and_mixed_databases(self):
        bad = self.manifest(1)
        bad['files'][0]['name'] = '../system01.dbf'
        with self.assertRaises(RuntimeError):
            store.check_manifest(bad, bad['stamp'])
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            _, remove, run = self.rman_patches(root, [self.manifest(1), self.manifest(8, '999')])
            with self.assertRaises(RuntimeError):
                store.prune_rman(True)
            remove.assert_not_called()
            run.assert_not_called()

    def test_head_metadata_mismatch_aborts(self):
        with patch.object(store, 'api', return_value={'ContentLength': 99,
                                                     'Metadata': {'sha256': 'a' * 64}}):
            with self.assertRaises(RuntimeError):
                store.verify_remote(self.manifest(1))

    def test_finalize_rechecks_s3_before_deleting_archives(self):
        item = self.manifest(1)
        item['archive_cutoff'] = '2026-11-01 00:00:00'
        with patch.object(store, 'read_object', return_value=json.dumps(item)), \
                patch.object(store, 'verify_remote', side_effect=RuntimeError('missing')), \
                patch.object(store, 'run') as run, patch.object(store, 'prune_rman') as prune:
            with self.assertRaises(RuntimeError):
                store.finalize_rman(item['stamp'], True)
            run.assert_not_called()
            prune.assert_not_called()

    def test_finalize_preview_never_runs_rman(self):
        item = self.manifest(1)
        item['archive_cutoff'] = '2026-11-01 00:00:00'
        with patch.object(store, 'read_object', return_value=json.dumps(item)), \
                patch.object(store, 'verify_remote'), patch.object(store, 'run') as run, \
                patch.object(store, 'prune_rman') as prune:
            store.finalize_rman(item['stamp'])
            run.assert_not_called()
            prune.assert_called_once_with(False)

    def test_failed_publish_does_not_create_success_marker(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            directory = Path(root) / '20261101-000000'
            directory.mkdir()
            for number in range(3):
                (directory / ('piece%d.bkp' % number)).write_bytes(b'backup')
            with patch.object(store, 'managed_root', return_value=Path(root)), \
                    patch.object(store, 'rman_cutoff', return_value='2026-11-01 00:00:00'), \
                    patch.object(store, 'aws') as aws, \
                    patch.object(store, 'verify_remote', side_effect=RuntimeError('mismatch')):
                with self.assertRaises(RuntimeError):
                    store.publish_rman(directory.name, '1509902213')
                self.assertFalse((directory / '_SUCCESS.json').exists())
                self.assertEqual(aws.call_count, 3)

    def test_legacy_preview_selects_only_old_backup_files(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parents[2] / 'tmp') as root:
            managed = Path(root) / 'rman-managed'
            legacy = Path(root) / 'rman-backups'
            legacy.mkdir()
            old = legacy / 'FREE_old.bkp'
            old.write_bytes(b'backup')
            os.utime(old, (1, 1))
            (legacy / 'system01.dbf').write_bytes(b'data')
            with patch.object(store, 'run') as run:
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    store.prune_legacy(managed, '20261101-000000', False)
                self.assertIn('FREE_old.bkp', output.getvalue())
                self.assertNotIn('system01.dbf', output.getvalue())
                run.assert_not_called()


class VersioningTests(unittest.TestCase):
    def test_versioned_or_suspended_bucket_is_blocked(self):
        for status in ('Enabled', 'Suspended'):
            with self.subTest(status=status), patch.object(store, 'api', return_value={'Status': status}):
                with self.assertRaises(RuntimeError):
                    store.require_unversioned_bucket()

    def test_unversioned_bucket_is_allowed(self):
        with patch.object(store, 'api', return_value={}):
            store.require_unversioned_bucket()


if __name__ == '__main__':
    unittest.main()
