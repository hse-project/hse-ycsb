/**
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright (C) 2015-2022 Micron Technology, Inc.
 *
 * Some parts of the code herein is derived from the ycsb rocksdb binding.
 */

package site.ycsb.db;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import com.micron.hse.Hse;
import com.micron.hse.HseException;
import com.micron.hse.Kvdb;
import com.micron.hse.Kvs;
import com.micron.hse.KvsCursor;
import com.micron.hse.Limits;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jnr.constants.platform.Errno;
import site.ycsb.ByteArrayByteIterator;
import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.workloads.CoreWorkload;

/**
 * HSE implementation of the YCSB benchmark.
 */
public class HseClient extends DB {
  private static final Logger LOGGER = LoggerFactory.getLogger(HseClient.class);
  private static final AtomicInteger REFERENCES = new AtomicInteger(0);

  /** YCSB keys are of the form userXXXXXXXXXXX... */
  private static final String YCSB_PREFIX = "user";
  private static final int YCSB_PREFIX_LENGTH = YCSB_PREFIX.length();
  /** 7 allows us to roughly break up the keyspace into 1000 groups. */
  private static final int PREFIX_LEN = 7;
  private static int valueBufSize;
  private static Kvdb kvdb;
  private static Kvs kvs;
  private static Path kvdbHome;
  private static String kvsName;

  // Re-use the same buffer for all keys
  private ByteBuffer keyBuffer;

  // Re-use the same buffer for all values
  private ByteBuffer valueBuffer;

  /**
   * Adapted from site.ycsb.db.rocksdb.RocksDBClient.
   */
  private Map<String, ByteIterator> deserializeValues(final ByteBuffer values,
      final Set<String> fields, final Map<String, ByteIterator> result) {
    while (values.hasRemaining()) {
      final int keyLen = values.getInt();
      final byte[] keyBytes = new byte[keyLen];
      values.get(keyBytes);
      final String key = new String(keyBytes);

      final int valueLen = values.getInt();

      if (fields == null || fields.contains(key)) {
        final byte[] valueBytes = new byte[valueLen];
        values.get(valueBytes);

        result.put(key, new ByteArrayByteIterator(valueBytes));
      } else {
        values.position(values.position() + valueLen);
      }
    }

    return result;
  }

  private static String getNextPrefix(final String prefix) {
    if (prefix == null) {
      return null;
    }

    assert prefix.contains(YCSB_PREFIX);

    final int prefixLength = prefix.length();

    /* The following could be removed if given a prefix like "user123",
     * we could add 1 to be "user124".
     */
    Long nextPrefix = new Long(0);
    try {
      nextPrefix = Long.parseLong(prefix.substring(YCSB_PREFIX_LENGTH, prefixLength));
      ++nextPrefix;
    } catch (final NumberFormatException e) {
      LOGGER.error("Prefix '" + prefix + "' contains non-numeric characters");
      throw new RuntimeException(e);
    }

    // If there are no more prefixes left
    if (nextPrefix > Math.pow(10, prefixLength - YCSB_PREFIX_LENGTH) - 1) {
      return null;
    }

    return YCSB_PREFIX + Long.toString(nextPrefix);
  }

  private static String getPrefix(final String key) {
    if (key == null || key.length() <= PREFIX_LEN) {
      return null;
    }

    return key.substring(0, PREFIX_LEN);
  }

  /**
   * Given a CSV-formatted nullable string, convert to list of key=value params.
   *
   * @param str CSV-formatted nullable string
   * @return List of key=value parameters
   */
  private static List<String> paramsToList(final String str) {
    final ArrayList<String> params = new ArrayList<>();

    if (str == null) {
      return params;
    }

    try (final CSVParser parser = CSVParser.parse(str, CSVFormat.DEFAULT)) {
      for (final CSVRecord record : parser) {
        params.addAll(record.toList());
      }
    } catch (final IOException e) {
      throw new RuntimeException(e);
    }

    return params;
  }

  /**
   * Borrowed from site.ycsb.db.rocksdb.RocksDBClient.
   */
  private byte[] serializeValues(final Map<String, ByteIterator> values) throws IOException {
    try (final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      final ByteBuffer buf = ByteBuffer.allocate(4);

      for (final Map.Entry<String, ByteIterator> value : values.entrySet()) {
        final byte[] keyBytes = value.getKey().getBytes(StandardCharsets.UTF_8);
        final byte[] valueBytes = value.getValue().toArray();

        buf.putInt(keyBytes.length);
        baos.write(buf.array());
        baos.write(keyBytes);

        buf.clear();

        buf.putInt(valueBytes.length);
        baos.write(buf.array());
        baos.write(valueBytes);

        buf.clear();
      }

      return baos.toByteArray();
    }
  }

  @Override
  public void init() throws DBException {
    synchronized (HseClient.class) {
      if (REFERENCES.getAndIncrement() == 0) {
        final Properties props = getProperties();

        Optional.ofNullable(props.get("hse.jniLibrary")).ifPresent(s -> {
            Hse.loadLibrary(Paths.get((String)s));
          }
        );

        final Path config = Optional.ofNullable(props.getProperty("hse.config"))
            .map(c -> Paths.get(c)).orElse(null);

        final List<String> hseGParams = paramsToList(props.getProperty("hse.gparams"));

        try {
          Hse.init(config, hseGParams.stream().toArray(String[]::new));
        } catch (final HseException e) {
          throw new DBException(e);
        }

        final String home = props.getProperty("hse.kvdb.home");
        if (home == null) {
          throw new DBException("hse.kvdb.home was not specified");
        }

        kvdbHome = Paths.get(home);

        final List<String> kvdbCParams = paramsToList(props.getProperty("hse.kvdb.cparams"));
        final List<String> kvdbRParams = paramsToList(props.getProperty("hse.kvdb.rparams"));
        final List<String> kvsCParams = paramsToList(props.getProperty("hse.kvs.cparams"));
        final List<String> kvsRParams = paramsToList(props.getProperty("hse.kvs.rparams"));

        final double scanProportion = Double.valueOf(props.getProperty(
            CoreWorkload.SCAN_PROPORTION_PROPERTY,
            CoreWorkload.SCAN_PROPORTION_PROPERTY_DEFAULT));
        if (scanProportion > 0) {
          // Parameters for workloads with scans, like workload E.
          kvdbRParams.add("csched_max_vgroups=1");
          kvsRParams.add("cn_cursor_vra=0");
        }

        // Create KVDB unless it has already been created.
        try {
          Kvdb.create(kvdbHome, kvdbCParams.stream().toArray(String[]::new));
          kvdb = Kvdb.open(kvdbHome, kvdbRParams.stream().toArray(String[]::new));
        } catch (final HseException e) {
          if (Errno.valueOf(e.getErrno()) == Errno.EEXIST) {
            try {
              kvdb = Kvdb.open(kvdbHome, kvdbRParams.stream().toArray(String[]::new));
            } catch (final HseException other) {
              throw new DBException(other);
            }
          } else {
            throw new DBException(e);
          }
        }

        kvsName = props.getProperty(CoreWorkload.TABLENAME_PROPERTY,
            CoreWorkload.TABLENAME_PROPERTY_DEFAULT);

        // Create KVS unless it has already been created.
        try {
          kvsCParams.add(String.format("prefix.length=%d", PREFIX_LEN));
          kvdb.kvsCreate(kvsName, kvsCParams.stream().toArray(String[]::new));
          kvs = kvdb.kvsOpen(kvsName, kvsRParams.stream().toArray(String[]::new));
        } catch (final HseException e) {
          if (Errno.valueOf(e.getErrno()) == Errno.EEXIST) {
            try {
              kvs = kvdb.kvsOpen(kvsName, kvsRParams.stream().toArray(String[]::new));
            } catch (final HseException other) {
              throw new DBException(other);
            }
          } else {
            throw new DBException(e);
          }
        }

        final String fieldCount = props.getProperty(CoreWorkload.FIELD_COUNT_PROPERTY,
            CoreWorkload.FIELD_COUNT_PROPERTY_DEFAULT);

        final String fieldLength = props.getProperty(CoreWorkload.FIELD_LENGTH_PROPERTY,
            CoreWorkload.FIELD_LENGTH_PROPERTY_DEFAULT);

        // Round valueBufSize to a multiple of 8192 >= valueBufSize
        // REVISIT: Old hsejni code called posix_memalign() to allocate memory on the page boundary
        valueBufSize = Integer.parseInt(fieldCount) * (Integer.parseInt(fieldLength) + 20)
            + 8191 & ~8191;
      }
    }

    keyBuffer = ByteBuffer.allocateDirect(Limits.KVS_KEY_LEN_MAX);
    valueBuffer = ByteBuffer.allocateDirect(valueBufSize);
  }

  @Override
  public void cleanup() {
    synchronized (HseClient.class) {
      if (REFERENCES.getAndDecrement() == 1) {
        try {
          kvs.close();
        } catch (final HseException e) {
          LOGGER.error(e.toString());
        }

        try {
          kvdb.close();
        } catch (final HseException e) {
          LOGGER.error(e.toString());
        }

        Hse.fini();
      }
    }
  }

  @Override
  public Status delete(final String table, final String key) {
    try {
      kvs.delete(key);
      return Status.OK;
    } catch (final HseException e) {
      e.printStackTrace();
      return Status.ERROR;
    }
  }

  @Override
  public Status insert(final String table, final String key, final Map<String, ByteIterator> values) {
    try {
      kvs.put(key, serializeValues(values));
      return Status.OK;
    } catch (final IOException | HseException e) {
      e.printStackTrace();
      return Status.ERROR;
    }
  }

  @Override
  public Status read(final String table, final String key, Set<String> fields, Map<String, ByteIterator> result) {
    try {
      final Optional<Integer> valueLen = kvs.get(key, valueBuffer);
      assert valueLen.isPresent();

      deserializeValues(valueBuffer, fields, result);

      valueBuffer.clear();

      return Status.OK;
    } catch (final HseException e) {
      e.printStackTrace();
      return Status.ERROR;
    }
  }

  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    String prefix = getPrefix(startkey);

    KvsCursor cursor;
    try {
      cursor = kvs.cursor(prefix);
    } catch (final HseException e) {
      e.printStackTrace();
      return Status.ERROR;
    }

    try {
      Optional<Integer> foundLen = cursor.seek(startkey, (byte[]) null);
      if (!foundLen.isPresent() || foundLen.get() == 0) {
        LOGGER.error("Scan failed for startkey=" + startkey);
        return Status.ERROR;
      }

      result.ensureCapacity(recordcount);

      int i = 0;
      int tries = 3;
      while (true) {
        try {
          for (; i < recordcount; i++) {
            cursor.read(keyBuffer, valueBuffer);

            final HashMap<String, ByteIterator> map = new HashMap<>();
            deserializeValues(valueBuffer, fields, map);

            keyBuffer.clear();
            valueBuffer.clear();

            result.add(map);
          }

          break;
        } catch (final EOFException e) {
          prefix = getNextPrefix(prefix);
          // Do nothing if this is a non-prefixed cursor or is the last prefix.
          if (prefix == null) {
            break;
          }

          /* This loop handles the case where we are at the end of a prefix and
           * haven't scanned 'recordcount' records yet. The getNextPrefix()
           * returns the next prefix in lexicographic order. If there are 3
           * successive empty prefixes, we fallback to a non-prefixed cursor
           * to avoid getting stuck in searching through a large sequence of
           * empty prefixes.
           */
          String tmpPrefix = null;
          if (tries-- == 0) {
            tmpPrefix = prefix;
            prefix = null;
          }

          cursor.close();
          cursor = kvs.cursor(prefix);

          if (tmpPrefix != null) {
            foundLen = cursor.seek(tmpPrefix, (byte[]) null);
            if (!foundLen.isPresent() || foundLen.get() == 0) {
              // Do nothing...
              break;
            }
          }
        }
      }
    } catch (final HseException e) {
      e.printStackTrace();
      return Status.ERROR;
    } finally {
      try {
        cursor.close();
      } catch (final HseException e) {
        e.printStackTrace();
      }
    }

    return Status.OK;
  }

  @Override
  public Status update(final String table, final String key, final Map<String, ByteIterator> values) {
    try {
      kvs.put(key, serializeValues(values));
      return Status.OK;
    } catch (final IOException | HseException e) {
      e.printStackTrace();
      return Status.ERROR;
    }
  }
}
