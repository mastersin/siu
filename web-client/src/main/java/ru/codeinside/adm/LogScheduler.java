/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Copyright (c) 2013, MPL CodeInside http://codeinside.ru
 */

package ru.codeinside.adm;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.DependsOn;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
@Startup
@DependsOn("BaseBean")
public class LogScheduler {

  @Inject
  LogConverter logConverter;
  ScheduledExecutorService executor;

  @PostConstruct
  public void init() {
    executor = Executors.newSingleThreadScheduledExecutor();
    int initialDelay = 24 - Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    if (initialDelay > 0) {
      executor.schedule(new LogCleaner(logConverter), 20, TimeUnit.SECONDS);
    }
    executor.scheduleAtFixedRate(new LogCleaner(logConverter), initialDelay, 24, TimeUnit.HOURS);
    executor.schedule(new LogHarvester(logConverter, executor), 10, TimeUnit.SECONDS);
    logConverter = null;
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
    executor = null;
  }

}
