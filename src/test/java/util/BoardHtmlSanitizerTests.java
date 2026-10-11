package util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import kr.or.oti.util.BoardHtmlSanitizer;

class BoardHtmlSanitizerTests {

    @Test
    void 본문_이미지는_내부_첨부파일만_허용하고_스크립트를_제거한다() {
        String sanitized = BoardHtmlSanitizer.sanitize(
                "<p>본문</p><script>alert(1)</script>"
                + "<img src=\"/attachments/10/view\" onerror=\"alert(1)\">"
                + "<img src=\"https://evil.example/image.png\">"
                + "<a href=\"javascript:alert(1)\">link</a>");

        assertTrue(sanitized.contains("본문"));
        assertTrue(sanitized.contains("/attachments/10/view"));
        assertFalse(sanitized.contains("<script"));
        assertFalse(sanitized.contains("onerror"));
        assertFalse(sanitized.contains("evil.example"));
        assertFalse(sanitized.contains("javascript:"));
    }

    @Test
    void youtubeIframe만허용한다() {
        String youtube = BoardHtmlSanitizer.sanitize(
                "<iframe src=\"https://www.youtube.com/embed/yQZ0BTOAsFE\"></iframe>"
        );
        assertTrue(youtube.contains(
                "https://www.youtube.com/embed/yQZ0BTOAsFE"
        ));

        String otherSite = BoardHtmlSanitizer.sanitize(
                "<iframe src=\"https://evil.example/embed/yQZ0BTOAsFE\"></iframe>"
        );
        assertFalse(otherSite.contains("evil.example"));

        String eventAttribute = BoardHtmlSanitizer.sanitize(
                "<iframe src=\"https://www.youtube.com/embed/yQZ0BTOAsFE\" "
                + "onload=\"alert(1)\"></iframe>"
        );
        assertFalse(eventAttribute.contains("onload"));
    }
}
