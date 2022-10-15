include sdk.mk

all: osgi

A2_CATEGORY = org.argeo.cms.jcr

BUNDLES = \
org.argeo.cms.jcr \
org.argeo.cms.jcr.ui \
org.argeo.cms.jcr.e4 \

DEP_CATEGORIES = \
org.argeo.tp \
org.argeo.tp.apache \
org.argeo.tp.jetty \
osgi/api/org.argeo.tp.osgi \
osgi/equinox/org.argeo.tp.eclipse \
swt/rap/org.argeo.tp.swt \
swt/rap/org.argeo.tp.swt.workbench \
org.argeo.tp.jcr \
org.argeo.cms \
swt/org.argeo.cms \
swt/rap/org.argeo.cms \
$(A2_CATEGORY)

clean:
	rm -rf $(BUILD_BASE)

include  $(SDK_SRC_BASE)/sdk/argeo-build/osgi.mk