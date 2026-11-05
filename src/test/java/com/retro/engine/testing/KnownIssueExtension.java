package com.retro.engine.testing;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Method;

/** Inverts the result of {@link KnownIssue} tests. See that annotation for the rules. */
public final class KnownIssueExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> context,
                                    ExtensionContext extensionContext) throws Throwable {
        KnownIssue issue = context.getExecutable().getAnnotation(KnownIssue.class);
        try {
            invocation.proceed();
        } catch (AssertionError expected) {
            throw new TestAbortedException("Known issue: " + issue.value() + " [" + expected.getMessage() + "]");
        }
        throw new AssertionError("This @KnownIssue test now passes, so the issue looks fixed. "
                + "Remove the annotation to keep the test as a regression guard: " + issue.value());
    }
}
