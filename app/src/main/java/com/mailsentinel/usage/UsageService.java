package com.mailsentinel.usage;

import com.mailsentinel.subscription.Plan;
import com.mailsentinel.subscription.PlanCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Service
public class UsageService {

    private final UsagePeriodRepository usagePeriodRepository;
    private final PlanCatalog planCatalog;

    public UsageService(UsagePeriodRepository usagePeriodRepository, PlanCatalog planCatalog) {
        this.usagePeriodRepository = usagePeriodRepository;
        this.planCatalog = planCatalog;
    }

    /**
     * Lazy rollover: if the user's most recent period has expired (or none exists),
     * a fresh one is created on read with a newly-snapshotted allowance. This is what
     * satisfies "billing period is active" as a precondition -- for a PREMIUM user a
     * current period always exists by construction, so there is no separate flag to check.
     *
     * NOT safe against two *simultaneous* rollovers creating two overlapping periods for
     * the same user in the same instant (a narrow window, once per ~30 days per user);
     * accepted as an unhandled edge case at this project's scale -- the concurrency
     * guarantee that matters is reserveOneScan's, within a single period.
     */
    @Transactional
    public UsagePeriod getOrCreateCurrentPeriod(Long userId) {
        Instant now = Instant.now();
        return usagePeriodRepository.findFirstByUserIdOrderByPeriodStartDesc(userId)
                .filter(period -> period.isActive(now))
                .orElseGet(() -> createNewPeriod(userId, now));
    }

    private UsagePeriod createNewPeriod(Long userId, Instant now) {
        int allowance = planCatalog.aiScansPerMonth(Plan.PREMIUM);
        Instant periodEnd = ZonedDateTime.ofInstant(now, ZoneOffset.UTC).plusMonths(1).toInstant();
        return usagePeriodRepository.save(new UsagePeriod(userId, now, periodEnd, allowance));
    }

    @Transactional
    public ReservationResult reserveOneScan(Long userId) {
        UsagePeriod period = getOrCreateCurrentPeriod(userId);
        int updated = usagePeriodRepository.reserveOneScan(period.getId(), Instant.now());
        if (updated == 0) {
            return new ReservationResult.LimitReached(period);
        }
        UsagePeriod refreshed = usagePeriodRepository.findById(period.getId()).orElseThrow();
        return new ReservationResult.Reserved(refreshed);
    }

    @Transactional
    public void refundOneScan(Long usagePeriodId) {
        usagePeriodRepository.refundOneScan(usagePeriodId, Instant.now());
    }

    @Transactional
    public UsageStatusResponse currentStatus(Long userId, boolean isPremium) {
        if (!isPremium) {
            return new UsageStatusResponse(Plan.FREE.name(), 0, 0, 0, null, null);
        }
        UsagePeriod period = getOrCreateCurrentPeriod(userId);
        return new UsageStatusResponse(
                Plan.PREMIUM.name(),
                period.getAllowance(),
                period.getScansUsed(),
                period.remaining(),
                period.getPeriodStart(),
                period.getPeriodEnd()
        );
    }
}
