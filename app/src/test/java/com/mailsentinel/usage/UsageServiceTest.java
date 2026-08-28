package com.mailsentinel.usage;

import com.mailsentinel.auth.User;
import com.mailsentinel.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class UsageServiceTest {

    @Autowired
    private UsageService usageService;

    @Autowired
    private UsagePeriodRepository usagePeriodRepository;

    @Autowired
    private UserRepository userRepository;

    private Long newUserId(String email) {
        return userRepository.save(new User(email, "irrelevant-hash")).getId();
    }

    @Test
    @Transactional
    void getOrCreateCurrentPeriodCreatesOnFirstCallAndReusesOnSecond() {
        Long userId = newUserId("usage1@example.com");
        UsagePeriod first = usageService.getOrCreateCurrentPeriod(userId);
        UsagePeriod second = usageService.getOrCreateCurrentPeriod(userId);
        assertEquals(first.getId(), second.getId(), "an active period must be reused, not recreated");
    }

    @Test
    @Transactional
    void reserveConsumesExactlyOneScan() {
        Long userId = newUserId("usage2@example.com");
        UsagePeriod period = usageService.getOrCreateCurrentPeriod(userId);
        assertEquals(0, period.getScansUsed());

        ReservationResult result = usageService.reserveOneScan(userId);
        assertTrue(result instanceof ReservationResult.Reserved);
        assertEquals(1, ((ReservationResult.Reserved) result).period().getScansUsed());
    }

    @Test
    @Transactional
    void reservationIsRejectedOnceAllowanceIsExhausted() {
        Long userId = newUserId("usage3@example.com");
        UsagePeriod period = usageService.getOrCreateCurrentPeriod(userId);
        // Directly exhaust the allowance via repeated reservation rather than assuming a
        // specific configured allowance value.
        int allowance = period.getAllowance();
        for (int i = 0; i < allowance; i++) {
            ReservationResult r = usageService.reserveOneScan(userId);
            assertTrue(r instanceof ReservationResult.Reserved, "reservation " + i + " should still succeed");
        }
        ReservationResult overLimit = usageService.reserveOneScan(userId);
        assertTrue(overLimit instanceof ReservationResult.LimitReached);
    }

    @Test
    @Transactional
    void refundReturnsAReservedScanToTheAllowance() {
        Long userId = newUserId("usage4@example.com");
        UsagePeriod period = usageService.getOrCreateCurrentPeriod(userId);
        ReservationResult reserved = usageService.reserveOneScan(userId);
        Long periodId = ((ReservationResult.Reserved) reserved).period().getId();
        assertEquals(1, usagePeriodRepository.findById(periodId).orElseThrow().getScansUsed());

        usageService.refundOneScan(periodId);

        assertEquals(0, usagePeriodRepository.findById(periodId).orElseThrow().getScansUsed());
    }

    @Test
    @Transactional
    void refundNeverGoesBelowZero() {
        Long userId = newUserId("usage5@example.com");
        UsagePeriod period = usageService.getOrCreateCurrentPeriod(userId);
        usageService.refundOneScan(period.getId());
        assertEquals(0, usagePeriodRepository.findById(period.getId()).orElseThrow().getScansUsed());
    }

    /**
     * The central concurrency guarantee this whole feature depends on: many simultaneous
     * reservation attempts against a fixed allowance must never let scans_used exceed
     * that allowance, and must never lose a legitimate reservation either.
     */
    @Test
    void concurrentReservationsNeverExceedTheAllowance() throws InterruptedException {
        Long userId = newUserId("usage-concurrency@example.com");
        int allowance = 10;
        UsagePeriod period = usagePeriodRepository.save(
                new UsagePeriod(userId, Instant.now(), Instant.now().plusSeconds(3600), allowance));

        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger reservedCount = new AtomicInteger(0);
        AtomicInteger limitReachedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    ReservationResult result = usageService.reserveOneScan(userId);
                    if (result instanceof ReservationResult.Reserved) {
                        reservedCount.incrementAndGet();
                    } else {
                        limitReachedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all reservation attempts should finish within 30s");
        executor.shutdown();

        assertEquals(allowance, reservedCount.get(), "exactly `allowance` reservations should succeed");
        assertEquals(threadCount - allowance, limitReachedCount.get());

        int finalScansUsed = usagePeriodRepository.findById(period.getId()).orElseThrow().getScansUsed();
        assertEquals(allowance, finalScansUsed, "scans_used must land exactly at the allowance, never over or under");
    }
}
