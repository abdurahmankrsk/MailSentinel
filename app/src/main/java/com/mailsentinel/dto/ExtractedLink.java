package com.mailsentinel.dto;

/**
 * A link extracted from an email body.
 *
 * @param href destination URL
 * @param anchorText visible anchor text if extracted from HTML, or null for plaintext
 */
public record ExtractedLink(
    String href,
    String anchorText
) {
    public ExtractedLink(String href) {
        this(href, null);
    }
}
