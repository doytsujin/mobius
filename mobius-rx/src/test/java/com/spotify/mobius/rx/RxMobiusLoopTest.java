/*
 * -\-\-
 * Mobius
 * --
 * Copyright (c) 2017-2020 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */
package com.spotify.mobius.rx;

import static com.spotify.mobius.Effects.effects;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSet;
import com.spotify.mobius.Connectable;
import com.spotify.mobius.Connection;
import com.spotify.mobius.ConnectionLimitExceededException;
import com.spotify.mobius.First;
import com.spotify.mobius.Init;
import com.spotify.mobius.Mobius;
import com.spotify.mobius.MobiusLoop;
import com.spotify.mobius.Next;
import com.spotify.mobius.Update;
import com.spotify.mobius.functions.Consumer;
import com.spotify.mobius.test.RecordingConnection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.Before;
import org.junit.Test;
import rx.Observable;
import rx.observers.AssertableSubscriber;
import rx.subjects.PublishSubject;

public class RxMobiusLoopTest {

  private RecordingConnection<Boolean> connection;
  private MobiusLoop.Builder<String, Integer, Boolean> builder;

  @Before
  public void setUp() throws Exception {
    connection = new RecordingConnection<>();
    builder =
        Mobius.loop(
            new Update<String, Integer, Boolean>() {
              @Nonnull
              @Override
              public Next<String, Boolean> update(String model, Integer event) {
                return Next.next(model + event.toString());
              }
            },
            new Connectable<Boolean, Integer>() {
              @Nonnull
              @Override
              public Connection<Boolean> connect(Consumer<Integer> output)
                  throws ConnectionLimitExceededException {
                return connection;
              }
            });
  }

  @Test
  public void shouldPropagateIncomingErrorsAsUnrecoverable() throws Exception {
    RxMobiusLoop<Integer, String, Boolean> loop =
        new RxMobiusLoop<>(builder, "", Collections.emptySet());
    PublishSubject<Integer> input = PublishSubject.create();

    AssertableSubscriber<String> subscriber = input.compose(loop).test();

    Exception expected = new RuntimeException("expected");
    input.onError(expected);
    subscriber.awaitTerminalEvent(1, TimeUnit.SECONDS);
    subscriber.assertError(new UnrecoverableIncomingException(expected));
    assertEquals(0, connection.valueCount());
  }

  @Test
  public void startModelAndEffects() throws Exception {
    RxMobiusLoop<Integer, String, Boolean> loop =
        new RxMobiusLoop<>(builder, "StartModel", ImmutableSet.of(true, false));
    final AssertableSubscriber<String> subscriber = Observable.just(1).compose(loop).test();

    waitForSubscriberValueCount(subscriber, 2);
    subscriber.assertValues("StartModel", "StartModel1");
    subscriber.assertNoErrors();
    assertEquals(2, connection.valueCount());
    connection.assertValuesInAnyOrder(true, false);
  }

  @Test
  public void shouldSupportStartingALoopWithAnInit() throws Exception {
    MobiusLoop.Builder<String, Integer, Boolean> withInit =
        builder.init(
            new Init<String, Boolean>() {
              @Nonnull
              @Override
              public First<String, Boolean> init(String model) {
                return First.first(model + "-init");
              }
            });

    Observable.Transformer<Integer, String> transformer = RxMobius.loopFrom(withInit, "hi");

    final AssertableSubscriber<String> observer = Observable.just(10).compose(transformer).test();

    waitForSubscriberValueCount(observer, 2);
    observer.assertValues("hi-init", "hi-init10");
  }

  private static void waitForSubscriberValueCount(
      AssertableSubscriber<String> subscriber, int count) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      if (subscriber.getValueCount() == count) {
        break;
      }

      Thread.sleep(50);
    }
  }

  @Test
  public void shouldThrowIfStartingALoopWithInitAndStartEffects() throws Exception {
    MobiusLoop.Builder<String, Integer, Boolean> withInit =
        builder.init(
            new Init<String, Boolean>() {
              @Nonnull
              @Override
              public First<String, Boolean> init(String model) {
                return First.first(model + "-init");
              }
            });

    Observable.Transformer<Integer, String> transformer =
        RxMobius.loopFrom(withInit, "hi", effects(true));

    Observable.just(10).compose(transformer).test().assertError(IllegalArgumentException.class);
  }
}
