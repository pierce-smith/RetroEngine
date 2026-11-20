package com.retro.engine.save;

import com.retro.engine.api.PublicApi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a script field whose value goes into save games. On load the value is restored after the script's
 * {@code onStart}, so {@code onStart} can set up defaults and the save then overrides them.
 *
 * <p>Supported types: {@code int}, {@code long}, {@code float}, {@code double}, {@code boolean} (and their boxed types),
 * {@code String}, enums, {@code int[]} and {@code float[]}. Anything else belongs in
 * {@link com.retro.engine.scripting.EntityScript#onSave(SaveRecord)}.</p>
 *
 * <pre>{@code
 * public class Player extends SimpleEntityScript {
 *     @Save int health = 100;
 *     @Save int ammo = 30;
 * }
 * }</pre>
 */
@PublicApi
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Save {}
