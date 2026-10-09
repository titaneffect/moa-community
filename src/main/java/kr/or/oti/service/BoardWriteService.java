package kr.or.oti.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import kr.or.oti.domain.AttachUsage;
import kr.or.oti.dto.BoardDTO;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BoardWriteService {
	
	private final BoardService boardService;
	private final BoardAttachService boardAttachService;
	
	@Transactional
	public long register(BoardDTO boardDTO, List<MultipartFile> uploadFiles,
			List<MultipartFile> contentImages, List<String> contentImageTokens) {
		
		Long bno = boardService.register(boardDTO);
		
		registerFiles(bno, uploadFiles);
		
		String originalContent = boardDTO.getContent();
		
		String updatedContent = boardAttachService
				.registerContentImages(bno, originalContent, contentImages, contentImageTokens);
		
		if(!Objects.equals(originalContent, updatedContent)) {
			boardDTO.setBno(bno);
			boardDTO.setContent(updatedContent);
			boardService.modify(boardDTO);
		}
		
		return bno;
	}
	
	@Transactional
	public void modify(BoardDTO boardDTO, List<MultipartFile> uploadFiles, List<Long> deleteAnoList,
			List<MultipartFile> contentImages, List<String> contentImageTokens) {
		
		Long bno = boardDTO.getBno();
		
		String updatedContent = boardAttachService
				.registerContentImages(bno, boardDTO.getContent(), contentImages, contentImageTokens);
	
		boardDTO.setContent(updatedContent);
		boardService.modify(boardDTO);
		
		registerFiles(bno, uploadFiles);
		
		if(deleteAnoList != null) {
			// 같은 첨부파일 번호가 중복 전달되더라도 한 번만 삭제
			for(Long ano : new LinkedHashSet<>(deleteAnoList)) {
				boardAttachService.remove(bno, ano);
			}
		}
		
		boardAttachService.removeUnusedContentImages(bno, updatedContent);
	}
	
	private void registerFiles(Long bno, List<MultipartFile> uploadFiles) {
		if(uploadFiles == null) {
			return;
		}
		
		for(MultipartFile uploadFile : uploadFiles) {
			if(uploadFile == null || uploadFile.isEmpty()) {
				continue;
			}
			
			boardAttachService.register(bno, uploadFile, AttachUsage.FILE);
		}
	}
}
