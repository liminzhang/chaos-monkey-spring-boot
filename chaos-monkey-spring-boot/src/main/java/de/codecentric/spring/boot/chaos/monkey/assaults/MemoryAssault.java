/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.codecentric.spring.boot.chaos.monkey.assaults;

import de.codecentric.spring.boot.chaos.monkey.component.MetricEventPublisher;
import de.codecentric.spring.boot.chaos.monkey.component.MetricType;
import de.codecentric.spring.boot.chaos.monkey.configuration.ChaosMonkeySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

import java.util.Vector;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Benjamin Wilms
 */
public class MemoryAssault implements ChaosMonkeyRuntimeAssault {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryAssault.class);
    private static final AtomicLong stolenMemory = new AtomicLong(0);

    private final Runtime runtime;
    private final AtomicBoolean inAttack = new AtomicBoolean(false);
    private final ChaosMonkeySettings settings;
    private final MetricEventPublisher metricEventPublisher;

    public MemoryAssault(Runtime runtime, ChaosMonkeySettings settings, MetricEventPublisher metricEventPublisher) {
        this.runtime = runtime;
        this.settings = settings;
        this.metricEventPublisher = metricEventPublisher;
    }

    @Override
    public boolean isActive() {
        return settings.getAssaultProperties().isMemoryActive();
    }

    @Override
    @Async
    public void attack() {
        LOGGER.info("Chaos Monkey - memory assault");

        // metrics
        if (metricEventPublisher != null)
            metricEventPublisher.publishMetricEvent(MetricType.MEMORY_ASSAULT);

        if (inAttack.compareAndSet(false, true)) {
            try {
                eatFreeMemory();
            } finally {
                inAttack.set(false);
            }
        }

        LOGGER.info("Chaos Monkey - memory assault cleaned up");
    }

    private void eatFreeMemory() {
        int minimumFreeMemoryPercentage = calculatePercentIncreaseValue(settings.getAssaultProperties().getMemoryFillIncrementFraction());

        @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
        Vector<byte[]> memoryVector = new Vector<>();
        long stolenHere = 0L;
        int percentIncreaseValue = calculatePercentIncreaseValue(calculatePercentageRandom());

        while (isActive() && runtime.freeMemory() >= minimumFreeMemoryPercentage && runtime.freeMemory() > percentIncreaseValue) {
            // only if ChaosMonkey in general is enabled, triggers a stop if the attack is canceled during an experiment

            // increase memory random percent steps
            memoryVector.add(createDirtyMemorySlice(percentIncreaseValue));
            stolenHere += percentIncreaseValue;
            long newStolenTotal = stolenMemory.addAndGet(percentIncreaseValue);
            metricEventPublisher.publishMetricEvent(MetricType.MEMORY_ASSAULT_MEMORY_STOLEN, newStolenTotal);

            LOGGER.debug("Chaos Monkey - memory assault increase, free memory: " + runtime.freeMemory());

            waitUntil(settings.getAssaultProperties().getMemoryMillisecondsWaitNextIncrease());
            percentIncreaseValue = calculatePercentIncreaseValue(settings.getAssaultProperties().getMemoryFillTargetFraction());
        }

        // Hold memory level and cleanUp after, only if experiment is running
        if (isActive()) {
            LOGGER.info("Memory fill reached, now sleeping and holding memory");
            waitUntil(settings.getAssaultProperties().getMemoryMillisecondsHoldFilledMemory());
        }

        // clean Vector
        memoryVector.clear();
        // quickly run gc for reuse
        Runtime.getRuntime().gc();

        long stolenAfterComplete = stolenMemory.addAndGet(-stolenHere);
        metricEventPublisher.publishMetricEvent(MetricType.MEMORY_ASSAULT_MEMORY_STOLEN, stolenAfterComplete);
    }

    private byte[] createDirtyMemorySlice(int size) {
        byte[] b = new byte[size];
        for (int idx = 0; idx < size; idx += 4096) { // 4096 is commonly the size of a mwmory page, forcing a commit
            b[idx] = 19;
        }

        return b;
    }

    private int calculatePercentIncreaseValue(double percentage) {
        return (int) Math.min(Integer.MAX_VALUE / 4, runtime.freeMemory() * percentage);
    }

    private double calculatePercentageRandom() {
        return ThreadLocalRandom.current().nextDouble(0.05, settings.getAssaultProperties().getMemoryFillTargetFraction());
    }

    private void waitUntil(int ms) {
        final long startNano = System.nanoTime();
        long now = startNano;
        while (startNano + TimeUnit.MILLISECONDS.toNanos(ms) > now && isActive()) {
            try {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(startNano - now);
                Thread.sleep(Math.min(100, ms - elapsed));
                now = System.nanoTime();
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
