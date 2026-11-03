package com.retro.engine.modding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public instance method taking exactly one {@link com.retro.engine.modding.events.ModEvent} subclass as a
 * handler for that event type when its object is passed to {@link ModEventBus#register(Object)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {
}
