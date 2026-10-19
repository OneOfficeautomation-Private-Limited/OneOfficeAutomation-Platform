package com.oneofficeautomation.sdk;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Outputs { Output[] value(); }
