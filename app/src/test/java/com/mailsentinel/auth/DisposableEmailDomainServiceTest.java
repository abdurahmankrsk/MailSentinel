package com.mailsentinel.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit test -- the service reads a classpath file and holds no Spring
 * collaborators, so there is nothing here that needs a context to be started.
 */
class DisposableEmailDomainServiceTest {

    private final DisposableEmailDomainService service = new DisposableEmailDomainService();

    @Test
    void blocklistIsActuallyLoaded() {
        // Guards the failure that would otherwise be invisible: an empty list leaves every
        // test below passing for the wrong reason and signup silently unprotected.
        assertTrue(service.blockedDomainCount() > 5000,
                "expected the vendored upstream list, got " + service.blockedDomainCount() + " domains");
    }

    @Test
    void rejectsWellKnownThrowawayProviders() {
        assertTrue(service.isDisposable("someone@mailinator.com"));
        assertTrue(service.isDisposable("someone@guerrillamail.com"));
        assertTrue(service.isDisposable("someone@yopmail.com"));
        assertTrue(service.isDisposable("someone@10minutemail.com"));
    }

    @Test
    void allowsMainstreamAndOrdinaryDomains() {
        assertFalse(service.isDisposable("someone@gmail.com"));
        assertFalse(service.isDisposable("someone@outlook.com"));
        assertFalse(service.isDisposable("someone@protonmail.com"));
        assertFalse(service.isDisposable("someone@icloud.com"));
        assertFalse(service.isDisposable("someone@example.com"));
    }

    @Test
    void aListedProviderAlsoCoversHostsBeneathIt() {
        assertTrue(service.isDisposable("someone@inbox.mailinator.com"));
        assertTrue(service.isDisposable("someone@deep.nested.mailinator.com"));
    }

    @Test
    void aSharedParentIsNotBlockedJustBecauseOneHostUnderItIs() {
        // Upstream lists individual dynamic-DNS hostnames such as 0-mailer.dynv6.net but
        // never the dynv6.net parent, precisely because the parent is shared with
        // legitimate users. The suffix walk must respect that distinction.
        assertTrue(service.isDisposable("someone@0-mailer.dynv6.net"));
        assertFalse(service.isDisposable("someone@my-own-home-server.dynv6.net"));
    }

    @Test
    void neverBlocksAWholeTopLevelDomain() {
        assertFalse(service.isDisposable("someone@com"));
        assertFalse(service.isDisposable("someone@some-domain-nobody-listed.com"));
    }

    @Test
    void matchesRegardlessOfCaseOrTrailingDot() {
        assertTrue(service.isDisposable("Someone@MailInator.COM"));
        assertTrue(service.isDisposable("someone@mailinator.com."));
        assertTrue(service.isDisposable("  someone@mailinator.com  ".trim()));
    }

    @Test
    void matchesTheUnicodeSpellingOfAPunycodeEntry() {
        // The list stores xn--yaho-sqa.com; a user would type the Unicode form.
        assertTrue(service.isDisposable("someone@yahóo.com"));
        assertTrue(service.isDisposable("someone@XN--YAHO-SQA.COM"));
    }

    @Test
    void treatsUnusableInputAsNotDisposableRatherThanThrowing() {
        assertFalse(service.isDisposable(null));
        assertFalse(service.isDisposable(""));
        assertFalse(service.isDisposable("no-at-sign"));
        assertFalse(service.isDisposable("trailing@"));
        assertFalse(service.isDisposable("someone@."));
    }

    @Test
    void requireNotDisposableNamesTheOffendingDomain() {
        DisposableEmailDomainException thrown = assertThrows(DisposableEmailDomainException.class,
                () -> service.requireNotDisposable("someone@inbox.mailinator.com"));

        assertTrue(thrown.getMessage().contains("inbox.mailinator.com"),
                "message should name the domain: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains("someone"),
                "message must not echo the local part: " + thrown.getMessage());

        assertDoesNotThrow(() -> service.requireNotDisposable("someone@example.com"));
    }
}
