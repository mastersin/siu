/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Copyright (c) 2013, MPL CodeInside http://codeinside.ru
 */

package ru.codeinside.sign;

import java.awt.*;
import java.security.AccessController;
import java.util.ArrayList;

import static java.awt.EventQueue.invokeLater;


final class CertDetector implements Runnable {

  final String[] TYPES = {"Rutoken", "J6CF", "OCF", "HDImage"};
  final CertConsumer consumer;
  final Panel ui;
  final Label label;
  final String fio;

  CertDetector(CertConsumer consumer, Panel ui, Label label) {
    this.consumer = consumer;
    this.ui = ui;
    this.label = label;
    this.fio = null;
  }

  CertDetector(CertConsumer consumer, Panel ui, Label label, String fio) {
    this.consumer = consumer;
    this.ui = ui;
    this.label = label;
    this.fio = fio;
  }

  @Override
  public void run() {
    ArrayList<Cert> certs = new ArrayList<Cert>();
    boolean hasJcp = false;
    int i = 0;
    for (String type : TYPES) {
      i++;
      invokeLater(new LabelUpdater(label, (100f * i / TYPES.length) + "%"));
      hasJcp |= detectInStorage(certs, type);
    }
    if (!hasJcp) {
      invokeLater(new NoJcp(consumer));
    } else {
      if (fio != null) {
        invokeLater(new CertSelector(ui, certs, consumer, fio));
      } else {
        invokeLater(new CertSelector(ui, certs, consumer));
      }
    }
  }

  boolean detectInStorage(ArrayList<Cert> certs, String type) {
    try {
      certs.addAll(AccessController.doPrivileged(new CertStorageDetector(type, consumer.getFilter())));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
