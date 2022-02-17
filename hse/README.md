# YCSB with HSE

This fork of YCSB (Yahoo!&reg; Cloud Serving Benchmark) integrates HSE
with YCSB 0.17.0.  The goal is to demonstrate the benefits of HSE
within an industry-standard benchmark.

The reader is assumed to be familiar with configuring and running YCSB,
as well as HSE concepts and terminology.
The information provided here is specific to using YCSB with HSE.

## Installing HSE

Clone the [`hse`](https://github.com/hse-project/hse) repo
and follow the documentation in
[`README.md`](https://github.com/hse-project/hse/blob/master/README.md)
to build and install HSE.

## Installing hse-java

Should you need to build and install `hse-java` into a local Maven repository,
refer to the
[`README.md`](https://github.com/hse-project/hse-java/blob/master/README.md)
for build and install instructions.

## Installing YCSB Dependencies

Depending on your Linux distribution and environment, you may need to
install additional packages to build YCSB.

Make sure you have Python 2 and Maven 3 installed on your system.

## Installing YCSB with HSE

Clone the [`hse-ycsb`](https://github.com/hse-project/hse-ycsb) repo
and checkout the latest release tag.  Releases are named `rA.B.C.D.E-hse` where

* `A.B.C` is the YCSB version (e.g., 0.17.0)
* `D.E` is our YCSB integration version

For example

```shell
git clone https://github.com/hse-project/hse-ycsb.git
cd hse-ycsb
git checkout rA.B.C.D.E-hse
```

Build YCSB with HSE as follows.

```shell
mvn -pl hse -am clean package
```

Extract the resulting tarball to a convenient directory.

```shell
tar xf ./hse/target/ycsb-hse-binding-0.17.0.tar.gz -C /opt/ycsb
```

## Configuring YCSB Options

YCSB with HSE adds the following command-line parameters to `ycsb`.

* `hse.jniLibrary`: Optional path to the HSE JNI library.
* `hse.config`: Optional path to an `hse.conf` file.
* `hse.kvdb.home`: Required path to the KVDB home directory for use with YCSB
* `hse.kvdb.cparams`: Optional KVDB cparams.
* `hse.kvdb.rparams`: Optional KVDB cparams.
* `hse.kvs.cparams`: Optional KVS cparams.
* `hse.kvs.rparams`: Optional KVS rparams.

## YCSB Data Storage

YCSB data is stored in an HSE KVDB.  The KVDB and KVS will be created
automatically if they don't already exist.

## Running YCSB with HSE

Create a KVDB for use with YCSB.

```shell
cd /opt/ycsb/ycsb-hse-binding-0.17.0
mkdir ycsbKVDB
hse kvdb create ${PWD}/ycsbKVDB
```

Run YCSB Workload A as follows.

```shell
LD_LIBRARY_PATH=/opt/hse/lib64 python2 ./bin/ycsb load hse -P workloads/workloada -p hse.kvdb.home=${PWD}/ycsbKVDB
LD_LIBRARY_PATH=/opt/hse/lib64 python2 ./bin/ycsb run hse -P workloads/workloada -p hse.kvdb.home=${PWD}/ycsbKVDB
```

> The HSE library path depends on both where you installed HSE and your
> Linux distribution.  You need to locate this directory to set
> `LD_LIBRARY_PATH` correctly.

## Storage and Benchmarking Tips

Please see the HSE [project documentation](https://hse-project.github.io/)
for information on configuring HSE storage and running benchmarks.
It contains important details on HSE file system requirements, configuration
options, performance tuning, and best practices for benchmarking.
