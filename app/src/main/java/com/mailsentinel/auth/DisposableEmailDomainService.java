package com.mailsentinel.auth;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Recognises throwaway mailbox providers, so an account cannot be created behind an
 * address that stops existing ten minutes later.
 *
 * The domain list is vendored from the community-maintained
 * <a href="https://github.com/disposable-email-domains/disposable-email-domains">disposable-email-domains</a>
 * project (CC0, public domain) and shipped as a classpath resource rather than fetched
 * at runtime: signup must not depend on a third-party host being reachable, and a
 * network blip must never silently degrade into accepting every address. The weekly
 * refresh workflow in {@code .github/workflows} is what keeps the vendored copy current.
 */
@Service
public class DisposableEmailDomainService {

    private static final String BLOCKLIST_RESOURCE = "disposable-email-domains.txt";

    private final Set<String> blockedDomains;

    public DisposableEmailDomainService() {
        this.blockedDomains = loadBlocklist();
    }

    /**
     * Is this address hosted by a known disposable provider?
     *
     * <p>Matching walks the domain's parent suffixes, so a listed provider covers hosts
     * beneath it: {@code inbox.mailinator.com} is caught by the {@code mailinator.com}
     * entry. The walk deliberately stops before the final label, because a bare
     * public suffix appearing in the list -- upstream has none today -- would otherwise
     * blocklist an entire TLD. It also means shared dynamic-DNS parents such as
     * {@code dynv6.net}, which upstream lists only as individual hostnames, keep
     * blocking exactly those hostnames and nothing else.
     */
    public boolean isDisposable(String email) {
        String domain = domainOf(email);
        return domain != null && matches(domain);
    }

    /**
     * Guard for the signup paths: same test as {@link #isDisposable}, but it reports the
     * matched domain in the failure. Callers get the check and the error message from one
     * place, so neither has to re-derive the domain from the address.
     */
    public void requireNotDisposable(String email) {
        String domain = domainOf(email);
        if (domain != null && matches(domain)) {
            throw new DisposableEmailDomainException(domain);
        }
    }

    private boolean matches(String domain) {
        for (String candidate = domain;
             candidate.indexOf('.') >= 0;
             candidate = candidate.substring(candidate.indexOf('.') + 1)) {
            if (blockedDomains.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Number of domains loaded; exposed so a startup log or test can assert the list arrived. */
    public int blockedDomainCount() {
        return blockedDomains.size();
    }

    /**
     * The list is stored as ASCII (punycode for the handful of internationalised
     * entries), so a Unicode address has to be encoded the same way before lookup --
     * otherwise the ASCII and Unicode spellings of one domain would disagree.
     */
    private String domainOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return null;
        }
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        // A trailing dot is the fully-qualified spelling of the same domain.
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty()) {
            return null;
        }
        try {
            return IDN.toASCII(domain).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return domain;
        }
    }

    /**
     * Read once at startup and keep in memory: the file is ~120 KB, and the alternative
     * -- re-reading per signup -- buys nothing.
     *
     * <p>A missing or empty resource is fatal rather than tolerated. Booting with an
     * empty set would leave every endpoint working and this defence quietly switched
     * off, which is the one failure mode nobody would notice.
     */
    private static Set<String> loadBlocklist() {
        Set<String> domains = new HashSet<>();
        ClassPathResource resource = new ClassPathResource(BLOCKLIST_RESOURCE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String domain = line.trim().toLowerCase(Locale.ROOT);
                // Upstream ships bare domains only; blank and '#' lines are tolerated so a
                // provenance header can be added to the vendored copy without a code change.
                if (domain.isEmpty() || domain.startsWith("#")) {
                    continue;
                }
                domains.add(domain);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + BLOCKLIST_RESOURCE, e);
        }
        if (domains.isEmpty()) {
            throw new IllegalStateException(BLOCKLIST_RESOURCE + " is empty");
        }
        return Set.copyOf(domains);
    }
}
