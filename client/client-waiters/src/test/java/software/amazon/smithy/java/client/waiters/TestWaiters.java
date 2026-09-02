/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.client.waiters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.java.client.waiters.backoff.BackoffStrategy;
import software.amazon.smithy.java.client.waiters.matching.Matcher;
import software.amazon.smithy.java.client.waiters.models.GetFoosInput;
import software.amazon.smithy.java.client.waiters.models.GetFoosOutput;
import software.amazon.smithy.java.core.error.ModeledException;
import software.amazon.smithy.java.core.schema.Schema;
import software.amazon.smithy.java.core.serde.ShapeSerializer;

public class TestWaiters {
    private static final String ID = "test-id";
    @Test
    void test() {
        var client = new MockClient(ID,
                List.of(
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("DONE")));
        var waiter = Waiter.builder(client::getFoosSync)
                .success(Matcher.output(o -> o.status().equals("DONE")))
                .build();
        // Waiter will throw on failure.
        waiter.wait(new GetFoosInput(ID), 20000);
    }

    @Test
    void testWaiterFailureMatch() {
        var client = new MockClient(ID,
                List.of(
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("DONE")));
        var waiter = Waiter.builder(client::getFoosSync)
                .failure(Matcher.output(o -> o.status().equals("DONE")))
                .build();
        var exc = assertThrows(
                WaiterFailureException.class,
                () -> waiter.wait(new GetFoosInput(ID), 20000));
        assertNull(exc.getCause());
        assertEquals(exc.getMessage(), "Waiter reached terminal, FAILURE state");
    }

    @Test
    void testWaiterTimesOut() {
        var client = new MockClient(ID,
                List.of(
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("BUILDING"),
                        new GetFoosOutput("BUILDING")));
        var waiter = Waiter.builder(client::getFoosSync)
                .backoffStrategy(BackoffStrategy.getDefault(10L, 20L))
                .failure(Matcher.output(o -> o.status().equals("DONE")))
                .build();
        var exc = assertThrows(
                WaiterFailureException.class,
                () -> waiter.wait(new GetFoosInput(ID), 5));
        assertNull(exc.getCause());
        assertTrue(exc.getMessage().contains("Waiter timed out after"));
    }

    @Test
    void testWaiterDoesNotPollAfterMaxWaitTime() {
        var attempts = new AtomicInteger();
        var waiter = Waiter.<GetFoosInput, GetFoosOutput>builder((input, override) -> {
            var status = attempts.incrementAndGet() == 1 ? "BUILDING" : "DONE";
            return new GetFoosOutput(status);
        })
                .backoffStrategy((attempt, remainingTime) -> remainingTime)
                .success(Matcher.output(o -> o.status().equals("DONE")))
                .build();

        var exc = assertThrows(
                WaiterFailureException.class,
                () -> waiter.wait(new GetFoosInput(ID), 100));

        assertEquals(1, attempts.get());
        assertEquals(1, exc.getAttemptNumber());
        assertTrue(exc.getMessage().contains("Waiter timed out after"));
    }

    @Test
    void testWaiterWrapsError() {
        var client = new MockClient(ID, List.of(new UnexpectedException("borked")));
        var waiter = Waiter.builder(client::getFoosSync)
                .failure(Matcher.output(o -> o.status().equals("DONE")))
                .build();
        var exc = assertThrows(
                WaiterFailureException.class,
                () -> waiter.wait(new GetFoosInput(ID), 10));
        assertEquals(exc.getMessage(), "Waiter encountered unexpected exception.");
        assertInstanceOf(UnexpectedException.class, exc.getCause());
    }

    private static final class UnexpectedException extends ModeledException {

        private UnexpectedException(String message) {
            super(null, message);
        }

        @Override
        public void serializeMembers(ShapeSerializer serializer) {
            // do nothing
        }

        @Override
        public <T> T getMemberValue(Schema member) {
            return null;
        }
    }
}
