package com.udacity.webcrawler.profiler;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Objects;

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

/**
 * Concrete implementation of the {@link Profiler}.
 */
final class ProfilerImpl implements Profiler {

  private final Clock clock;
  private final ProfilingState state = new ProfilingState();
  private final ZonedDateTime startTime;

  @Inject
  ProfilerImpl(Clock clock) {
    this.clock = Objects.requireNonNull(clock);
    this.startTime = ZonedDateTime.now(clock);
  }

  boolean isKlassProfiled(Class<?> klass){
    Method[] methods = klass.getDeclaredMethods();
    if (methods.length == 0) return false;
    for (Method method : methods){
      if (method.getAnnotation(Profiled.class) != null){
        return true;
      }
    }
    return false;
  }

  @Override
  public <T> T wrap(Class<T> klass, T delegate) {
    Objects.requireNonNull(klass);
    if (!isKlassProfiled(klass)){
      throw new IllegalArgumentException(
              "Interface does not contain a profiled method"
      );
    }

    InvocationHandler invocationHandler = new ProfilingMethodInterceptor(clock, delegate, state);

    return (T) Proxy.newProxyInstance(
            klass.getClassLoader(),
            new Class[] {klass},
            invocationHandler
    );
  }

  @Override
  public void writeData(Path path) throws IOException {
    Objects.requireNonNull(path);
    if (Files.notExists(path)){
      Files.createFile(path);
    }
    try(BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)){
      writeData(writer);
    }
  }

  @Override
  public void writeData(Writer writer) throws IOException {
    writer.write("Run at " + RFC_1123_DATE_TIME.format(startTime));
    writer.write(System.lineSeparator());
    state.write(writer);
    writer.write(System.lineSeparator());
  }
}
