include sdk.mk

all: osgi
	
install: osgi-install

uninstall: osgi-uninstall

A2_CATEGORY = org.argeo.cms.jcr

BUNDLES = \
org.argeo.cms.jcr \
org.argeo.slc.repo \
org.argeo.slc.rpmfactory \
org.argeo.slc.jcr \
swt/org.argeo.cms.jcr.ui \
swt/org.argeo.tool.swt \
swt/org.argeo.tool.devops.e4 \

DEP_CATEGORIES = \
org.argeo.tp \
org.argeo.tp.build \
org.argeo.tp.jcr \
org.argeo.tp.sdk \
org.argeo.tp.utils \
osgi/equinox/org.argeo.tp.osgi \
osgi/equinox/org.argeo.tp.eclipse \
swt/rap/org.argeo.tp.swt \
swt/rap/org.argeo.tp.swt.workbench \
org.argeo.cms \
org.argeo.slc \
swt/org.argeo.cms \
swt/org.argeo.slc \
swt/rap/org.argeo.cms \
swt/rap/org.argeo.slc \
$(A2_CATEGORY)

clean:
	rm -rf $(BUILD_BASE)

include  $(SDK_SRC_BASE)/sdk/argeo-build/osgi.mk