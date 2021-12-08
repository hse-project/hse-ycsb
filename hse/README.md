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

You must

* Use HSE version 2.0 or higher
* Perform `meson setup` specifying the `-Dycsb=true` option

If you previously built HSE without the `-Dycsb=true` option,
run the following commands in the `hse` repo directory.

```shell
meson setup <builddir> --reconfigure -Dycsb=true
meson compile -C <builddir>
meson install -C <builddir>
```

In the above, `<builddir>` is the build directory you specified when
previously compiling HSE, which is commonly named `build`.
The default install directory is `/opt/hse`, which you can override
by configuring the build with either `-Dprefix=$prefix` or `--prefix=$prefix`.


## Installing YCSB Dependencies

Depending on your Linux distribution and environment, you may need to
install additional packages to build YCSB.
For example, building YCSB requires Maven 3.

To help you with this process, below are examples of the packages required
for several common Linux distributions.  These are **in addition to**
the packages required to build HSE.

### RHEL 8 Packages

```shell
sudo dnf install maven python2
```

### Ubuntu 18.04 Packages

```shell
sudo apt install maven
```


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
HSE_JAR="/opt/hse/lib64/hsejni.jar"
mvn install:install-file -Dfile="${HSE_JAR}" -DgroupId=test.org.hse -DartifactId=hse -Dversion=0.0 -Dpackaging=jar
mvn -pl hse -am clean package
```

> The path to `hsejni.jar` depends on both where you installed
> HSE and your Linux distribution.  You need to locate this file to
> set `HSE_JAR` correctly.

Extract the resulting tarball to a convenient directory.

```shell
tar xf ./hse/target/ycsb-hse-binding-0.17.0.tar.gz -C /opt/ycsb
```


## Configuring YCSB Options

YCSB with HSE adds the following command-line parameters to `ycsb`.

* `hse.kvdb_home` is the path to the KVDB home directory for use with YCSB


## YCSB Data Storage

YCSB data is stored in an HSE KVDB.  This KVDB must be created before
running the `ycsb` command.  However, `ycsb` will create the required KVS
in the KVDB.


## Running YCSB with HSE

Create a KVDB for use with YCSB.

```shell
cd /opt/ycsb/ycsb-hse-binding-0.17.0
mkdir ycsbKVDB
hse kvdb create ${PWD}/ycsbKVDB
```

Run YCSB Workload A as follows.

```shell
LD_LIBRARY_PATH=/opt/hse/lib64 python2 ./bin/ycsb load hse -P workloads/workloada -p hse.kvdb_home=${PWD}/ycsbKVDB
LD_LIBRARY_PATH=/opt/hse/lib64 python2 ./bin/ycsb run hse -P workloads/workloada -p hse.kvdb_home=${PWD}/ycsbKVDB
```

> The HSE library path depends on both where you installed HSE and your
> Linux distribution.  You need to locate this directory to set
> `LD_LIBRARY_PATH` correctly.


## Storage and Benchmarking Tips

Please see the HSE [project documentation](https://hse-project.github.io/)
for information on configuring HSE storage and running benchmarks.
It contains important details on HSE file system requirements, configuration
options, performance tuning, and best practices for benchmarking.
