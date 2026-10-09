package kr.or.oti.controller;

import java.nio.charset.StandardCharsets;
import java.security.Principal;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import kr.or.oti.domain.AttachUsage;
import kr.or.oti.dto.BoardAttachDTO;
import kr.or.oti.dto.BoardDTO;
import kr.or.oti.dto.CafeBoardDTO;
import kr.or.oti.service.BoardAttachService;
import kr.or.oti.service.BoardService;
import kr.or.oti.service.CafeBoardAccessService;
import kr.or.oti.service.CafeBoardService;
import kr.or.oti.service.FileStorageService;
import lombok.RequiredArgsConstructor;

@Controller
@RequestMapping("/attachments")
@RequiredArgsConstructor
public class BoardAttachController {

	private final BoardService boardService;
	private final CafeBoardService cafeBoardService;
	private final CafeBoardAccessService cafeBoardAccessService;
	private final BoardAttachService boardAttachService;
	private final FileStorageService fileStorageService;
	
	@GetMapping("/{ano}/view")
	public ResponseEntity<Resource> view(@PathVariable("ano")Long ano,
			Principal principal){
		
		//1. DB에서 첨부파일 정보 조회
		BoardAttachDTO attach = boardAttachService.get(ano);
		
		if(attach == null) {
			return ResponseEntity.notFound().build();
		}
		
		BoardDTO board = boardService.get(attach.getBno());
		
		CafeBoardDTO cafeBoard =
				cafeBoardService.getById(board.getCafeBoardId());
		
		String username = principal == null ? null : principal.getName();
		
		if(!cafeBoardAccessService.canAccess(cafeBoard.getCafeId(),
				username, cafeBoard.getReadRole())) {
			
			throw new AccessDeniedException("이 첨부파일을 조회할 권한이 없습니다.");
		}
		
		//2. 디스크에서 실제 파일 조회
		Resource resource = fileStorageService.load(attach);
		
		//3. 본문 이미지이며 허용된 이미지 형식일 때만 화면에 표시한다.
		String contentType = attach.getContentType();
		
		boolean allowedImageType =
				"image/png".equals(contentType)
				|| "image/jpeg".equals(contentType)
				|| "image/gif".equals(contentType)
				|| "image/webp".equals(contentType);
		
		boolean inlineImage =
				attach.getAttachUsage() == AttachUsage.CONTENT_IMAGE
				&& allowedImageType;
		
		
		MediaType mediaType = inlineImage ?
				MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM;
		
		//4. 다운로드 파일명에서 경로와 제어 문자를 제거한다.
		String fileName = attach.getFileName();
		
		if(fileName == null || fileName.trim().isEmpty()) {
			fileName = "attachment";
		} else {
			fileName = fileName.replace('\\', '/');
			fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
			fileName = fileName.replaceAll("\\p{Cntrl}", "_");
			
			if(fileName.trim().isEmpty()
					|| ".".equals(fileName)
					|| "..".equals(fileName)){
				fileName = "attachment";
			}
		}
		
		ContentDisposition disposition = inlineImage ?
				ContentDisposition.inline().build()
				: ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();
		
		//5. 실제 파일을 HTTP 응답 본문에 담는다.
		return ResponseEntity.ok()
				.contentType(mediaType)
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.header("X-Content-Type-Options", "nosniff")
				.body(resource);
	}
}
