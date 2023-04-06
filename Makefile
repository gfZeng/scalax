
-include .env
-include .$(env).env
export

.DEFAULT_GOAL := .default

SHELL = bash

SBT ?= sbt
JAVA ?= java
PYTHON ?= python3
RSYNC = rsync -av \
          --exclude '*.jar' \
          --exclude 'semanticdb' \
          --exclude '*.tasty' \
          --exclude '*.zip'

OS := $(shell uname -s)

LINUX_MAVEN_DIR=.cache/coursier/v1/https/repo1.maven.org/maven2

ifeq ($(OS), Linux)
	MAVEN_DIR ?= $(HOME)/$(LINUX_MAVEN_DIR)
  XARGS_I = I
else
	MAVEN_DIR ?= $(HOME)/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2
  XARGS_I = J
endif

CLASSPATH_FILE ?= .java.classpath
SRCDIR ?= src

MYDIR = $(shell dirname $(abspath $(lastword $(MAKEFILE_LIST))))

help:
	@cat $(MAKEFILE_LIST)


## Java & Scala
clean:
	@rm -rf $(CLASSPATH_FILE)
	@$(SBT) clean

compile:
	@$(SBT) 'fgRunMain $$.classpath $(CLASSPATH_FILE)'
	@perl -pi -e "s{`pwd`/}{}g" $(CLASSPATH_FILE)
	@perl -pi -e "s{$(MAVEN_DIR)}{\\\$$MAVEN_DIR}g" $(CLASSPATH_FILE)

compile!: clean compile
JAVACMD = $(JAVA) -cp "$$(eval echo `cat $(CLASSPATH_FILE)`)"

fast-compile:
	@$(SBT) --client compile

launch:
	@$(JAVACMD) $$(eval 'echo "$(APP)"')

run:
	@$(JAVACMD) '$$.launch' $(APP)


#.ONESHELL:
#source.changed:
#	@ #
#	[ ! -f "$(CLASSPATH_FILE)" ] && exit
#	timestr=`date -r "$(CLASSPATH_FILE)" +'%Y-%m-%d %H:%M:%S'`
#	find "$(SRCDIR)" -type f -newermt  "$$timestr" | grep $(SRCDIR) > /dev/null

export PYTHONPATH ?= $(shell python3 -m site --user-site)

#py/run: PYTHONPATH := $(PYTHONPATH):$(MYDIR)/py
py/run:
	$(PYTHON) -m $(APP)

jupyter:
	jupyter lab --no-browser


ide/clean:
	rm -rf target project/{target,src,project} .idea .bsp .bloop

fast-deploy deploy: CLASSPATHS = cat $(CLASSPATH_FILE) | tr ':' '\n'

deploy: HOST=$(firstword $(subst :, , $(DEST)))
deploy: DIR=$(lastword $(subst :, , $(DEST)))
deploy: fast-deploy
	@ssh -T $(HOST) 'mkdir -p $(LINUX_MAVEN_DIR)'
	@$(CLASSPATHS) \
		| grep MAVEN_DIR \
		| perl -pe 's{(MAVEN_DIR)}{$$1/.}g' \
		| xargs -I{} sh -c 'echo {}' \
		| xargs -L100 -$(XARGS_I){} rsync -avR {} $(HOST):$(LINUX_MAVEN_DIR)


fast-deploy: DEPLOY_FILE = .deploy.java.classpath
fast-deploy:
	@$(CLASSPATHS) |grep -v '^/' | tr '\n' ':' > $(DEPLOY_FILE)
	@scp $(DEPLOY_FILE)                $(DEST)/$(CLASSPATH_FILE)
	@$(CLASSPATHS) |grep '^/' | xargs dirname
	@$(CLASSPATHS) |grep '^/' | xargs dirname | xargs -I{} $(RSYNC) {} $(DEST)/target
	@test -f makefile && rsync -L makefile $(DEST) || :
	@$(RSYNC) target/scala-*           $(DEST)/target

## for web
web/build:
	cd web/cryptoMarket/ && pnpm i && pnpm run build

web/deploy:
	rsync -avz web/cryptoMarket/dist    $(DEST)


define serviceinfo
[Unit]
ConditionPathExists=$(PWD)

[Service]
WorkingDirectory=$(PWD)
ExecStart=/usr/bin/env make ${CMD} env=%I
ExecReload=/bin/kill -HUP \$$MAINPID
Restart=always
endef

export serviceinfo
service/install: DIR = .
service/install:
	@echo "$${serviceinfo}" > $(DIR)/$(SERVICE)@.service
	@test "$(RESTART_SEC)" != ""  && echo  RestartSec=$(RESTART_SEC) >> $(DIR)/$(SERVICE)@.service || :

.default: ACTION ?= launch
.default:
	@$(MAKE) $(ACTION)

