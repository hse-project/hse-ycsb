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
          LOGGER.error(e.getMessage(), e);
        }

        try {
          kvdb.close();
        } catch (final HseException e) {
          LOGGER.error(e.getMessage(), e);
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
      LOGGER.error(e.getMessage(), e);
      return Status.ERROR;
    }
  }

  @Override
  public Status insert(final String table, final String key, final Map<String, ByteIterator> values) {
    try {
      kvs.put(key, serializeValues(values));
      return Status.OK;
    } catch (final IOException | HseException e) {
      LOGGER.error(e.getMessage(), e);
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
      LOGGER.error(e.getMessage(), e);
      return Status.ERROR;
    }
  }

  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields,
      Vector<HashMap<String, ByteIterator>> result) {
    try (final KvsCursor cursor = kvs.cursor()) {
      final Optional<Integer> foundLen = cursor.seek(startkey, (byte[]) null);
      if (!foundLen.isPresent() || foundLen.get() == 0) {
        LOGGER.error("Scan failed for startkey=" + startkey);
        return Status.ERROR;
      }

      result.ensureCapacity(recordcount);

      for (int i = 0; i < recordcount; i++) {
        cursor.read(keyBuffer, valueBuffer);

        final HashMap<String, ByteIterator> map = new HashMap<>();
        deserializeValues(valueBuffer, fields, map);

        keyBuffer.clear();
        valueBuffer.clear();

        result.add(map);
      }
    } catch (final HseException e) {
      LOGGER.error(e.getMessage(), e);
      return Status.ERROR;
    } catch (final EOFException e) {
      return Status.OK;
    }

    return Status.OK;
  }

  @Override
  public Status update(final String table, final String key, final Map<String, ByteIterator> values) {
    try {
      kvs.put(key, serializeValues(values));
      return Status.OK;
    } catch (final IOException | HseException e) {
      LOGGER.error(e.getMessage(), e);
      return Status.ERROR;
    }
  }
}
