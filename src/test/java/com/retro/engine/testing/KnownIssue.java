package com.retro.engine.testing;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that describes correct behavior the engine does not have yet. The test keeps running, and:
 * <ul>
 *   <li>if it fails an assertion, as expected, it is reported as skipped with this reason, so the build stays green;</li>
 *   <li>if it passes, the build FAILS until this annotation is removed, so a fix can't go unrecorded and the test
 *       guards the fix from then on.</li>
 * </ul>
 * Only assertion failures count as expected. Any other exception is a real failure.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@ExtendWith(KnownIssueExtension.class)
public @interface KnownIssue {
    /** What is wrong today, and where to read about it. */
    String value();
}
