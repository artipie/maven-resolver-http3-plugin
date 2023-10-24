/*
 * The MIT License (MIT) Copyright (c) 2020-2022 artipie.com
 * https://github.com/artipie/maven-adapter/blob/master/LICENSE.txt
 */
package com.artipie.helloworld;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Properties;

public class HelloWorld {

  public static void main(final String[] args) {
    final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("h:mm:ss a 'on' MMMM d, yyyy'.'");
    final LocalDateTime now = LocalDateTime.now();

    System.out.println("Hello, World! The current time is " + dtf.format(now));

    final Map<String, String> env = System.getenv();
    for (final String key: env.keySet()) {
      System.out.println("ENV: " + key + " == " + env.get(key));
    }
    final Properties ps = System.getProperties();
    for (final Object key: ps.keySet()) {
      System.out.println("PROP: " + key + " == " + ps.get(key));
    }
  }
}
