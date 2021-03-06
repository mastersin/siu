/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Copyright (c) 2013, MPL CodeInside http://codeinside.ru
 */

package ru.codeinside.adm;


import org.codehaus.jackson.map.ObjectMapper;
import ru.codeinside.adm.database.ExternalGlue;
import ru.codeinside.adm.database.HttpLog;
import ru.codeinside.adm.database.SmevLog;
import ru.codeinside.adm.database.SoapPacket;
import ru.codeinside.gses.webui.osgi.LogCustomizer;
import ru.codeinside.gws.log.format.Metadata;
import ru.codeinside.gws.log.format.Pack;

import javax.ejb.DependsOn;
import javax.ejb.Singleton;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionManagement;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static javax.ejb.TransactionAttributeType.REQUIRES_NEW;

@TransactionManagement
@TransactionAttribute
@Singleton
@Stateless
@DependsOn("BaseBean")
public class LogConverter {

  @PersistenceContext(unitName = "myPU")
  private EntityManager em;

  final private Logger logger = Logger.getLogger(getClass().getName());

  private String dirPath;

  protected String getLazyDirPath() {
    return LogCustomizer.getStoragePath();
  }

  protected SmevLog findLogEntry(String marker) {
    List<SmevLog> rs = em.
      createQuery("select l from SmevLog l where l.marker = :marker", SmevLog.class)
      .setMaxResults(1)
      .setParameter("marker", marker)
      .getResultList();
    return rs.isEmpty() ? null : rs.get(0);
  }

  @TransactionAttribute(REQUIRES_NEW)
  public boolean logToBd() {

    String pathInfo = getDirPath();
    if (isEmpty(pathInfo)) {
      return false;
    }

    List<File> logPackages = new ArrayList<File>();
    listLowDirs(new File(pathInfo), logPackages);
    if (logPackages.isEmpty()) {
      return false;
    }

    try {
      for (File logPackage : logPackages) {
        SmevLog log = getOepLog(logPackage.getName());
        File[] logFiles = logPackage.listFiles();
        if (logFiles != null) {
          for (File logFile : logFiles) {
            String name = logFile.getName();
            if (name.startsWith("log-http")) {
              int lastIndex = name.lastIndexOf("-");
              int index = name.indexOf("-", "log-http".length());

              log.setClient(Boolean.valueOf(name.substring(lastIndex + 1)));

              String httpString = readFileAsString(logFile);
              if (Boolean.valueOf(name.substring(index + 1, lastIndex))) {
                log.setSendHttp(new HttpLog(httpString));
              } else {
                log.setReceiveHttp(new HttpLog(httpString));
              }
            }
            if (name.startsWith("log-metadata")) {
              ObjectMapper objectMapper = new ObjectMapper();
              Metadata metadata = objectMapper.readValue(logFile, Metadata.class);
              log.setError(metadata.error);
              log.setLogDate(metadata.date);
              if (em != null) {
                List<Long> l = em.createQuery("select b.id from Bid b where b.processInstanceId=:processInstanceId", Long.class)
                  .setParameter("processInstanceId", metadata.processInstanceId)
                  .getResultList();
                if (!l.isEmpty()) {
                  log.setBidId(l.get(0).toString());
                }
              }
              if (metadata.clientRequest != null) {
                log.setSendPacket(getSoapPacket(metadata.clientRequest));
                setInfoSystem(log, log.getSendPacket().getSender());
              }
              if (metadata.clientResponse != null) {
                log.setReceivePacket(getSoapPacket(metadata.clientResponse));
                setInfoSystem(log, log.getReceivePacket().getSender());
              }
              if (metadata.serverResponse != null) {
                log.setSendPacket(getSoapPacket(metadata.serverResponse));
                setInfoSystem(log, log.getSendPacket().getSender());
              }
              if (metadata.serverRequest != null) {
                log.setReceivePacket(getSoapPacket(metadata.serverRequest));
                setInfoSystem(log, log.getReceivePacket().getSender());
              }

            }

            if (isEmpty(log.getBidId())) {
              ExternalGlue glue = getExternalGlue(log);
              if (glue != null) {
                log.setBidId(glue.getBidId());
              }
            }
            persist(log);
            deleteFile(logFile);
          }
        }
        deleteFile(logPackage);
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "failure", e);
      return false;
    }
    return true;
  }

  @TransactionAttribute(REQUIRES_NEW)
  public void logToZip(int cleanLogDepth) {
    Calendar cal = Calendar.getInstance();
    cal.add(Calendar.DATE, -cleanLogDepth);
    Date edgeDate = cal.getTime();
    List<SmevLog> oepLogs = em.createQuery("select o from SmevLog o where o.date < :date", SmevLog.class)
      .setParameter("date", edgeDate)
      .getResultList();

    for (SmevLog log : oepLogs) {
      String httpSendName;
      String httpReceiveName;

      if (log.isClient()) {
        httpReceiveName = "log-http-false-true";
        httpSendName = "log-http-true-true";

      } else {
        httpReceiveName = "log-http-false-false";
        httpSendName = "log-http-true-false";
      }
      File zip = new File(getZipPath(), log.getMarker());
      ZipOutputStream zipOut = null;
      try {
        zipOut = new ZipOutputStream(new FileOutputStream(zip));
        if (log.getSendHttp() != null) {
          ZipEntry httpSend = new ZipEntry(httpSendName);
          zipOut.putNextEntry(httpSend);
          zipOut.write(log.getSendHttp().getData());
        }
        if (log.getReceiveHttp() != null) {
          ZipEntry httpReceive = new ZipEntry(httpReceiveName);
          zipOut.putNextEntry(httpReceive);
          zipOut.write(log.getReceiveHttp().getData());
        }
        ObjectMapper objectMapper = new ObjectMapper();
        Metadata metadata = new Metadata();
        metadata.error = log.getError();
        metadata.date = log.getLogDate();
        List<String> ids = em.createQuery("select b.processInstanceId from Bid b where b.id = :id", String.class)
          .setParameter("id", log.getBidId()).getResultList();
        if (!ids.isEmpty()) {
          metadata.processInstanceId = ids.get(0);
        }
        if (log.isClient()) {
          metadata.clientRequest = getPack(log.getSendPacket());
          metadata.clientResponse = getPack(log.getReceivePacket());
        } else {
          metadata.serverResponse = getPack(log.getSendPacket());
          metadata.serverRequest = getPack(log.getReceivePacket());
        }
        ZipEntry logMetadata = new ZipEntry("log-metadata");
        zipOut.putNextEntry(logMetadata);
        zipOut.write(objectMapper.writeValueAsBytes(metadata));
        em.remove(log);


      } catch (IOException e) {
        // skip
      } finally {
        try {
          if (zipOut != null) {
            zipOut.close();
          }
        } catch (IOException e) {
          // skip
        }
      }
    }
    em.flush();
  }

  // ---- internals ----

  private SmevLog getOepLog(String marker) {
    SmevLog entry = findLogEntry(marker);
    if (entry == null) {
      entry = new SmevLog();
      entry.setDate(new Date());
      entry.setMarker(marker);
    }
    return entry;
  }

  private String getDirPath() {
    if (dirPath == null) {
      dirPath = getLazyDirPath();
    }
    return dirPath;
  }

  private ExternalGlue getExternalGlue(SmevLog log) {
    ExternalGlue externalGlue = getExternalGlue(log.getReceivePacket());
    if (externalGlue != null) {
      return externalGlue;
    }
    return getExternalGlue(log.getSendPacket());
  }

  private ExternalGlue getExternalGlue(SoapPacket soapPacket) {
    if (em == null) {
      return null;
    }
    if (soapPacket != null && !isEmpty(soapPacket.getOriginRequestIdRef())) {
      List resultList = em.createQuery("select g from ExternalGlue g where g.requestIdRef = :requestIdRef").setParameter("requestIdRef", soapPacket.getOriginRequestIdRef()).getResultList();
      if (!resultList.isEmpty()) {
        return (ExternalGlue) resultList.get(0);
      }
    }
    return null;
  }

  private String readFileAsString(File file) {
    try {
      StringBuffer fileData = new StringBuffer();
      BufferedReader reader = new BufferedReader(new FileReader(file));
      char[] buf = new char[1024];
      int numRead;
      while ((numRead = reader.read(buf)) != -1) {
        String readData = String.valueOf(buf, 0, numRead);
        fileData.append(readData);
      }
      reader.close();
      return fileData.toString();
    } catch (IOException e) {
      return null;
    }
  }

  private SoapPacket getSoapPacket(Pack packet) {
    SoapPacket result = new SoapPacket();
    result.setSender(packet.sender);
    result.setRecipient(packet.recipient);
    result.setOriginator(packet.originator);
    result.setService(packet.serviceName);
    result.setTypeCode(packet.typeCode);
    result.setStatus(packet.status);
    result.setDate(packet.date);
    result.setRequestIdRef(packet.requestIdRef);
    result.setOriginRequestIdRef(packet.originRequestIdRef);
    result.setServiceCode(packet.serviceCode);
    result.setCaseNumber(packet.caseNumber);
    result.setExchangeType(packet.exchangeType);
    return result;
  }

  private Pack getPack(SoapPacket packet) {
    if (packet == null) {
      return null;
    }
    Pack result = new Pack();
    result.sender = packet.getSender();
    result.recipient = packet.getRecipient();
    result.originator = packet.getOriginator();
    result.serviceName = packet.getService();
    result.typeCode = packet.getTypeCode();
    result.status = packet.getStatus();
    result.date = packet.getDate();
    result.requestIdRef = packet.getRequestIdRef();
    result.originRequestIdRef = packet.getOriginRequestIdRef();
    result.serviceCode = packet.getServiceCode();
    result.caseNumber = packet.getCaseNumber();
    result.exchangeType = packet.getExchangeType();
    return result;
  }

  private String getZipPath() {
    String instanceRoot = System.getProperty("com.sun.aas.instanceRoot");
    File file;
    if (instanceRoot != null) {
      file = new File(new File(new File(instanceRoot), "logs"), "zip");
    } else {
      file = new File("/var/", "zip");
    }
    if (!file.exists()) {
      file.mkdirs();
    }
    return file.getAbsolutePath();
  }

  private boolean isEmpty(String str) {
    return str == null || str.length() == 0;
  }

  private void listLowDirs(File root, List<File> lowDirs) {
    if (root != null) {
      File[] files = root.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file != null) {
            if (file.isDirectory()) {
              listLowDirs(file, lowDirs);
            } else {
              lowDirs.add(root);
              break;
            }
          }
        }
      }
    }
  }

  private void setInfoSystem(SmevLog log, String sender) {
    if (isEmpty(log.getInfoSystem()) && !isEmpty(sender)) {
      log.setInfoSystem(sender);
    }
  }

  private void deleteFile(File file) {
    if (canDelete()) {
      file.delete(); //обработать откат транзакции
    }
  }

  private void persist(SmevLog log) {
    if (em != null) {
      em.persist(log);
    }
  }

  private boolean canDelete() {
    return em != null;
  }

}
