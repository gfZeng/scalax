#!/usr/bin/env bash

#\
define(){ :; }; endef(){ :; }
#\
for arg in "$@"; do [[ $arg == *=* ]] && export $arg; done
#\
set -a; [ -f .env ] && source .env; [ -f .$env.env ] && source .$env.env; set +a;
#\
file $0 | grep "binary data" > /dev/null && exec java -cp $0 $APP || exec make -f $0 $@

define LOAD_ENV
set -a; [ -f .env ] && source .env; [ -f .$$env.env ] && source .$$env.env; set +a;
endef

.DEFAULT_GOAL := .default
SHELL = bash
.ONESHELL:
.PHONY:


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
	REV = tac
else
	MAVEN_DIR ?= $(HOME)/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2
  XARGS_I = J
	REV = tail -r
endif

CLASSPATH_FILE ?= .java.classpath
SRCDIR ?= src


export

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
	@$(LOAD_ENV)
	@exec $(JAVACMD) $$APP

run:
	@$(LOAD_ENV)
	@exec $(JAVACMD) '$$.launch' $$APP


#.ONESHELL:
#source.changed:
#	@ #
#	[ ! -f "$(CLASSPATH_FILE)" ] && exit
#	timestr=`date -r "$(CLASSPATH_FILE)" +'%Y-%m-%d %H:%M:%S'`
#	find "$(SRCDIR)" -type f -newermt  "$$timestr" | grep $(SRCDIR) > /dev/null


py/run:
	@$(LOAD_ENV)
	@exec $(PYTHON) -m $$APP

jupyter:
	jupyter lab --no-browser


ide/clean:
	rm -rf target project/{target,src,project} .idea .bsp .bloop

fast-deploy deploy jar: CLASSPATHS = cat $(CLASSPATH_FILE) | tr ':' '\n'

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

bin jar jar!: JARNAME ?= $(shell basename $(PWD))
jar:
	@test -f $(JARNAME).jar && exit 0
	tmp=__temp__; pwd=$(PWD)
	mkdir -p $$tmp
	for f in `$(CLASSPATHS) | $(REV)`; do
		case $$f in
			*.jar)
				echo unpack $$f to $$tmp
				cd $$tmp && jar xf `eval echo $$f`
				cd $$pwd
			;;
			*)
				$(RSYNC) -R $$f/./ $$tmp
		esac
	done
	cd $$pwd; echo packing to $(JARNAME).jar
	jar cf $(JARNAME).jar -C $$tmp .
	rm -rf $$tmp

jar!:
	rm $(JARNAME).jar
	$(MAKE) -s jar

bin: BINNAME ?= $(JARNAME)
bin: jar
	@cat $(MAKEFILE_LIST) $(JARNAME).jar > $(BINNAME)
	chmod +x $(BINNAME)

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
Environment=CMD=launch
EnvironmentFile=-$(PWD)/.%I.env
ExecStart=/usr/bin/env make $$CMD env=%I
ExecReload=/bin/kill -HUP $$MAINPID
Restart=always
endef

export serviceinfo
service/install: DIR = ~/.config/systemd/user
service/install: SERVICE ?= $(shell basename $(PWD))
service/install:
	@test $(OS) == Linux ||  { echo $(OS) not supports systemd; exit 0; }
	@mkdir -p $(DIR)
	@echo "$${serviceinfo}" > $(DIR)/$(SERVICE)@.service
	@test "$(RESTART_SEC)" != ""  && echo  RestartSec=$(RESTART_SEC) >> $(DIR)/$(SERVICE)@.service || :
	@systemctl --user daemon-reload

.default: ACTION ?= launch
.default:
	@exec $(MAKE) -s $(ACTION)

.%:
	@:

%:
	@$(LOAD_ENV)
	@exec $(MAKE) -s .$@
