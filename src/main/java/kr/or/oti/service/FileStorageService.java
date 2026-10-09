package kr.or.oti.service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import kr.or.oti.domain.BoardAttachVO;
import kr.or.oti.dto.BoardAttachDTO;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FileStorageService {
	
	@Value("${upload.root}")
	private String uploadRoot;
	
	public Resource load(BoardAttachDTO boardAttachDTO) {
		
		Path rootPath = Paths.get(uploadRoot)
							.toAbsolutePath()
							.normalize();
		
		String storedFileName = boardAttachDTO.getUuid()
									+ "_" + boardAttachDTO.getFileName();
		
		Path filePath = rootPath
							.resolve(boardAttachDTO.getUploadPath())
							.resolve(storedFileName)
							.normalize();
		
		// 지정된 업로드 폴더 밖으로 벗어나는 경로 방지
		if(!filePath.startsWith(rootPath)) {
			throw new IllegalStateException("잘못된 파일 경로입니다.");
		}
		
		try {
			Resource resource = new UrlResource(filePath.toUri());
			
			if(!resource.exists() || !resource.isReadable()) {
				throw new IllegalStateException("파일을 찾을 수 없습니다.");
			}
			
			return resource;
		} catch(MalformedURLException e){
			throw new IllegalStateException("파일 경로를 불러올 수 없습니다.", e);
		}
	}
	
	public BoardAttachVO store(MultipartFile uploadFile) {		
		requireTransaction();
		
		//1. 비어 있는 파일 검사	
		if(uploadFile == null || uploadFile.isEmpty()) {
			throw new IllegalArgumentException("저장할 파일이 없습니다.");
		}
		
		String originalFileName = uploadFile.getOriginalFilename();
		
		//2. 사용자가 업로드한 파일명
		if(originalFileName == null || originalFileName.trim().isEmpty()) {
			throw new IllegalArgumentException("파일명이 없습니다.");
		}
		
		//3. 파일명에 포함될 수 있는 경로 제거
		originalFileName = Paths
							.get(originalFileName)
							.getFileName()
							.toString();
		
		//4. 물리적 파일명 충돌 방지를 위한 UUID
		String uuid = UUID.randomUUID().toString();
		
		String storedFileName = uuid + "_" + originalFileName;
		
		//5. 날짜별 상대 저장 경로
		String uploadPath = LocalDate.now().format(
				DateTimeFormatter.ofPattern("yyyy/MM/dd"));
							
		//6. 실제 저장 폴더 경로
		Path uploadDirectory = Paths
								.get(uploadRoot)
								.resolve(uploadPath);
		
		//7. 최종 파일 경로
		Path targetPath = uploadDirectory.resolve(storedFileName);
		
		
		// 파일 쓰기 전에 등록해서, 쓰다가 실패한 파일도 정리 대상에 포함된다.
		TransactionSynchronizationManager.registerSynchronization(
				new TransactionSynchronization() {
					
					@Override
					public void afterCompletion(int status) {
						if(status == STATUS_ROLLED_BACK) {
							cleanupFile(targetPath);
						} else if(status == STATUS_UNKNOWN) {
							// DB 결과가 불명확하면 파일을 임의로 삭제하지 않는다.
							log.error("트랜잭션 결과를 확인할 수 없습니다. 파일 확인 필요: {}", targetPath);
						}
					}
				}
		);

		try {
			//해당 날짜의 폴더가 없으면 생성
			Files.createDirectories(uploadDirectory);
			
			//실제 파일 저장
			uploadFile.transferTo(targetPath.toFile());
		} catch(IOException | RuntimeException e) {
			// 파일 쓰기 도중 만들어진 불완전한 파일 정리
			cleanupFile(targetPath);
			
			throw new IllegalStateException("파일 저장에 실패했습니다: " + originalFileName, e);
		}
		
		//8. DB에 저장할 첨부파일 정보 반환
		return BoardAttachVO.builder()
							.uuid(uuid)
							.fileName(originalFileName)
							.uploadPath(uploadPath)
							.fileSize(uploadFile.getSize())
							.contentType(uploadFile.getContentType())
							.build();
	}
	
	public void delete(BoardAttachDTO boardAttachDTO) {
		requireTransaction();
		
		Path rootPath = Paths.get(uploadRoot)
							.toAbsolutePath()
							.normalize();
		
		String storedFileName = boardAttachDTO.getUuid()
				+ "_" + boardAttachDTO.getFileName();
		
		Path filePath = rootPath
							.resolve(boardAttachDTO.getUploadPath())
							.resolve(storedFileName)
							.normalize();
		
		if(!filePath.startsWith(rootPath)) {
			throw new IllegalStateException("잘못된 파일 경로입니다.");
		}
		
		TransactionSynchronizationManager.registerSynchronization(
				new TransactionSynchronization() {
					
					@Override
					public void afterCommit() {
						cleanupFile(filePath);
					}
				}
		);							
	}
	
	private void requireTransaction() {
		if(!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			
			throw new IllegalStateException("파일 변경은 트랜잭션 안에서 실행해야 합니다.");
		}
	}
	
	private void cleanupFile(Path filePath) {
		try {
			Files.deleteIfExists(filePath);
		} catch(IOException | RuntimeException e) {
			// 정리 실패가 원래 오류를 덮지 않도록 기록한다.
			log.error("파일 정리에 실패했습니다. {}", filePath, e);
		}
	}
}
