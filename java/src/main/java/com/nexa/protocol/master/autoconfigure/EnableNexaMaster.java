package com.nexa.protocol.master.autoconfigure;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(NexaMasterAutoConfiguration.class)
@Documented
public @interface EnableNexaMaster {
}
