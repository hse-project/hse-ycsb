#
#    SPDX-License-Identifier: Apache-2.0
#
#    Copyright (C) 2015-2020 Micron Technology, Inc.
#

define HELP_TEXT

Makefile Help
----------------------------------

Primary Targets:

    all             -- clean, build RPMs
    check-hse       -- verify hse RPM is installed
    cleansrcs       -- clean out rpmbuild/SOURCES
    dist            -- build YCSB distribution tarball
    help            -- print this message
    rpm             -- build RPM
    srcs            -- prepare rpmbuild/SOURCES

  The easiest thing is to type "make" on the command line.

Configuration Variables:

  These get set on the command line.

    NOTMP           -- set to anything to place rpmbuild in homedir instead
                       of in /tmp/<username>

Products:
  hse-ycsb-LargeRecordCount RPM in rpmbuild/RPMS/noarch.
  hse-ycsb-LargeRecordCount RPM in rpmbuild/SRPMS.

  RPMs currently use the following format for their release string:

     RELEASENUM.HSESHA.noarch
endef

ifdef NOTMP
	TOPDIR:="$(HOME)/rpmbuild"
	DEB_TOPDIR:="$(HOME)/debbuild"
else
	TOPDIR:="/tmp/$(shell id -u -n)/rpmbuild"
	DEB_TOPDIR:="/tmp/$(shell id -u -n)/debbuild"
endif

DISTRO_ID_LIKE := $(shell . /etc/os-release && echo $$ID_LIKE)
ifeq ($(DISTRO_ID_LIKE),debian)
	PACKAGE_TARGET = deb
else
	PACKAGE_TARGET = rpm
endif

JENKINS_BUILDNO?=0
REL_CANDIDATE?=FALSE

#
# IMPORTANT NOTES FOR BUILD
#
# RHEL 7 needs to use SCL as follows -
#
# scl enable rh-maven33 devtoolset-7  "make rpm"
#
# Fedora 25 needs to use the toolchain from /shared as follows -
#
# LD_LIBRARY_PATH="/shared/toolchains/rh/devtoolset-7/root/usr/lib64:/shared/toolchains/rh/devtoolset-7/root/usr/lib:/shared/toolchains/rh/devtoolset-7/root/usr/lib64/dyninst:/shared/toolchains/rh/devtoolset-7/root/usr/lib/dyninst:/shared/toolchains/rh/devtoolset-7/root/usr/lib64:/shared/toolchains/rh/devtoolset-7/root/usr/lib" PATH="/shared/toolchains/rh/devtoolset-7/root/usr/bin:$PATH" make rpm
#

RPMSRCDIR:=$(TOPDIR)/SOURCES
TOOLSDIR:=/shared/tools

#
# variables for prebuilt jars/binaries
#
HSE_JAR:="/usr/share/hse/jni/hsejni.jar"

RPM_QUERY:=$(shell rpm -q hse >/dev/null; echo $$?)

ifeq ($(RPM_QUERY), 0)
    HSEVERSION:=$(shell rpm -q hse --qf "%{VERSION}")
else
    HSEVERSION:=$(shell dpkg-query --showformat='${Version}\n' --show hse | cut -d'-' -1)
endif

HSESHA:=.$(word 6,$(subst ., ,$(shell hse version)))
ifeq ($(HSESHA),.)
    HSESHA:=.nogit
endif
YCSBSHA:=.$(shell git rev-parse --short=7 HEAD)
ifeq ($(YCSBSHA),.)
    YCSBSHA:=.nogit
endif
TSTAMP:=.$(shell date +"%Y%m%d.%H%M%S")

HSE_YCSB_VER=$(shell cat hse/VERSION)
HSE_BINDING_VER=$(shell cut -d . -f 4-  hse/VERSION)

ifeq ($(REL_CANDIDATE), FALSE)
    RPM_RELEASE:=${JENKINS_BUILDNO}$(HSESHA)$(YCSBSHA)
    DEB_VERSION:=$(HSE_YCSB_VER)-${JENKINS_BUILDNO}$(HSESHA)$(YCSBSHA)
else
    RPM_RELEASE:=${JENKINS_BUILDNO}
    DEB_VERSION:=$(HSE_YCSB_VER)-${JENKINS_BUILDNO}
endif

#
# variables for debian
#
DEB_PKGNAME:=hse-ycsb-$(DEB_VERSION)_amd64
DEB_PKGDIR:=$(DEB_TOPDIR)/$(DEB_PKGNAME)
DEB_ROOTDIR:=$(DEB_TOPDIR)/$(DEB_PKGNAME)/opt/hse-ycsb

.PHONY: all check-hse cleansrcs dist help srcs rpm deb
all:	rpm

check-hse:
	#
	# User or Jenkins must install hse before executing this makefile.
	#
	@if [ ! -f /usr/share/hse/jni/hsejni.jar ]; \
	then \
	    echo "Missing hse package!  Cannot build!"; \
	    exit 1; \
	fi

cleansrcs:
	rm -rf $(RPMSRCDIR)/*

cleanbuilds:
	rm -rf $(TOPDIR)/{BUILD,RPMS,SRPMS}

dist: check-hse
	mvn versions:set-property -DnewVersion=$(HSE_BINDING_VER) -Dproperty=hse.version
	mvn install:install-file -Dfile=$(HSE_JAR) -DgroupId=test.org.hse\
		-DartifactId=hse -Dversion=$(HSE_BINDING_VER) -Dpackaging=jar
	mvn clean package

help:
	@echo
	$(info $(HELP_TEXT))

rpm: dist srcs
	cp hse-ycsb-LargeRecordCount.spec $(RPMSRCDIR)
	cp distribution/target/ycsb-0.17.0.tar.gz $(RPMSRCDIR)
	QA_RPATHS=0x0002 rpmbuild -vv -ba \
		--define="tstamp $(TSTAMP)" \
		--define="hseversion $(HSEVERSION)" \
		--define="hsesha $(HSESHA)" \
		--define="ycsbsha $(YCSBSHA)" \
		--define="_topdir $(TOPDIR)" \
		--define="pkgrelease $(RPM_RELEASE)" \
		--define="buildno $(JENKINS_BUILDNO)" \
		--define="hseycsbversion $(HSE_YCSB_VER)" \
		$(RPMSRCDIR)/hse-ycsb-LargeRecordCount.spec

deb: dist
	rm -rf $(DEB_TOPDIR)
	mkdir -p $(DEB_TOPDIR)
	mkdir -p $(DEB_PKGDIR)
	mkdir -p $(DEB_ROOTDIR)
	cp distribution/target/ycsb-0.17.0.tar.gz $(DEB_TOPDIR)
	cd $(DEB_TOPDIR) && tar xf ycsb-0.17.0.tar.gz
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/bin $(DEB_ROOTDIR)
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/hse-binding $(DEB_ROOTDIR)
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/lib $(DEB_ROOTDIR)
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/mongodb-binding $(DEB_ROOTDIR)
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/rocksdb-binding $(DEB_ROOTDIR)
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/workloads $(DEB_ROOTDIR)
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/LICENSE.txt $(DEB_ROOTDIR)
	cp -a $(DEB_TOPDIR)/ycsb-0.17.0/NOTICE.txt $(DEB_ROOTDIR)
	mkdir $(DEB_PKGDIR)/DEBIAN
	cp debian/control $(DEB_PKGDIR)/DEBIAN
	sed -i 's/@VERSION@/$(DEB_VERSION)/' $(DEB_PKGDIR)/DEBIAN/control
	cd $(DEB_TOPDIR) && dpkg-deb -b $(DEB_PKGNAME)

package: $(PACKAGE_TARGET)

srcs: cleansrcs
	mkdir -p $(TOPDIR)/{BUILD,RPMS,SOURCES,SPECS,SRPMS}

