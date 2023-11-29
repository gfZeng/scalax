#!/usr/bin/env bash


define LOAD_ENV
set -a; [ -f .env ] && source .env; [ -f .$$env.env ] && source .$$env.env; set +a;
endef

.DEFAULT_GOAL := .default
CMD ?= run
SHELL = bash
.ONESHELL:
.PHONY: bin


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
CLASSPATH = $$(eval echo `cat $(CLASSPATH_FILE)`)
SRCDIR ?= src


export

help:
	@cat $(MAKEFILE_LIST)


## Java & Scala
clean:
	@rm -rf $(CLASSPATH_FILE)
	@find . -type d -name target |xargs rm -rf

compile:
	@$(SBT) 'fgRunMain $$.classpath $(CLASSPATH_FILE)'
	@perl -pi -e "s{`pwd`/}{}g" $(CLASSPATH_FILE)
	@perl -pi -e "s{$(MAVEN_DIR)}{\\\$$MAVEN_DIR}g" $(CLASSPATH_FILE)

compile!: clean compile

fast-compile:
	@$(SBT) --client compile

launch:
	@$(LOAD_ENV)
	@exec $(JAVA) -cp "$(CLASSPATH)" '$$.launch' $$APP

define RUNSHELL
export PYTHONPATH=$$PYTHONPATH:$$0
if $(PYTHON) -c "import importlib.util as iu, sys; sys.exit(0 if iu.find_spec(\"$${APP%% *}\") else 1)" 2> /dev/null; then
	exec $(PYTHON) -m $$APP
else
	exec $(JAVA) -cp "$(CLASSPATH)" $$APP
fi
endef

run:
	@$(LOAD_ENV)
	$(RUNSHELL)


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


ide/clean: clean
	rm -rf .idea .bsp .bloop .metals .vscode project/metals.sbt project/project

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


bin %/jar %/jar! jar jar!: BINNAME ?= $(shell basename $(PWD))
bin %/jar %/jar! jar jar!: JARFILE ?= $(shell basename $(PWD)).jar
bin %/jar %/jar! jar jar!: CLASSPATH = $$0
.py/jar: 
	@test -f $(JARFILE) && flags=uf || flags=cf
	for path in `echo $$PYTHONPATH | tr ':' '\n'`; do
		jar $$flags $(JARFILE) -C $$path .
	done
	
jar:
	@test -f $(JARFILE) && update=true || update=false
	tmp=__temp__; pwd=$(PWD)
	mkdir -p $$tmp
	for f in `$(CLASSPATHS) | $(REV)`; do
		case $$f in
			*.jar)
				$$update && continue
				echo unpack $$f to $$tmp
				cd $$tmp && jar xf `eval echo $$f`
				cd $$pwd
			;;
			*)
				$(RSYNC) -R $$f/./ $$tmp
		esac
	done
	cd $$pwd
	if $$update; then echo "Updating $(JARFILE)"; flag=uf; else echo "Creating $(JARFILE)"; flag=cf; fi
	jar $$flag $(JARFILE) -C $$tmp .
	rm -rf $$tmp

jar! py/jar!:
	@rm -rf $(JARFILE)
	$(MAKE) -s $(subst !, , $@)

define BINSHELL
for arg in "$$@"; do [[ $$arg == *=* ]] && export $$arg || export APP="$$APP $$arg"; done
$(LOAD_ENV)
APP=$${APP:-$(APP)}
$(RUNSHELL)
endef

fuck:
	@echo '$(BINSHELL)'

bin:
	@echo '#!/usr/bin/env bash' > $(BINNAME)
	@echo '$(BINSHELL)'          >> $(BINNAME)
	@cat $(JARFILE)            >> $(BINNAME)
	chmod +x $(BINNAME)



define serviceinfo
[Unit]
ConditionPathExists=$(PWD)

[Service]
WorkingDirectory=$(PWD)
Environment=CMD=$(CMD)
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

.default:
	@exec $(MAKE) -s $(CMD)

.%:
	@:

%:
	@$(LOAD_ENV)
	@exec $(MAKE) -s .$@
