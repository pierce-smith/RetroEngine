package com.retro.engine.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the engine's public API: what scripts and mods may rely on across 1.x releases.
 *
 * <ul>
 *   <li>On a type: every public and protected member of the type, and of its public nested types, is API, except
 *       members marked {@link Internal}.</li>
 *   <li>On a member of an unmarked type: only that member is API. Engine classes that mix script calls with engine
 *       plumbing (such as {@code SceneWorld}) are marked this way.</li>
 * </ul>
 *
 * Anything public that isn't marked is engine code: scripts can still call it, but it may change in any release.
 * The marked surface is recorded in {@code api/retroengine.api}, and {@code PublicApiTest} fails when it drifts from
 * that file or when an API signature mentions a type that isn't API. Run {@code ./gradlew apiDump} after an intended
 * change and review the diff.
 */
@PublicApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface PublicApi {
}
