package com.mailsentinel.dto;

import java.util.List;

/**
 * The links a scan will actually examine, plus how many it had to leave out.
 *
 * <p>The count is the whole reason this type exists. Both scan paths cap the work a
 * single request can cause, which is correct -- an uncapped scan is a cheap CPU sink
 * on an endpoint that needs no account. What was not correct was capping silently:
 * the checks went on to report "No link uses a raw IP address as the host" about a
 * set of links that had been truncated before anything looked at them. Carrying the
 * dropped count means every check that says "nothing found" can also say how much it
 * looked at.
 *
 * @param analyzed the deduplicated links, capped at {@code MAX_LINKS_PER_SCAN}
 * @param dropped how many further distinct links were left unexamined, 0 if none
 */
public record ExtractedLinks(List<ExtractedLink> analyzed, int dropped) {}
