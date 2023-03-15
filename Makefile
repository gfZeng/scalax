
-include .env
-include .$(env).env
export


SBT ?= sbt
JAVA ?= java
PYTHON ?= python3
RSYNC = rsync -av \
          --exclude '*.jar' \
          --exclude 'semanticdb' \
          --exclude '*.tasty' \
          --exclude '*.zip'

OS := $(shell uname -s)

ifeq ($(OS), Linux)
	MAVEN_DIR ?= $(HOME)/.cache/coursier/v1/https/repo1.maven.org/maven2
else
	MAVEN_DIR ?= $(HOME)/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2
endif

CLASSPATH_FILE ?= .java.classpath
SRCDIR ?= src

MYDIR = $(shell dirname $(abspath $(lastword $(MAKEFILE_LIST))))

help:
	@cat Makefile


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

ide/clean:
	rm -rf target project/{target,src,project} .idea .bsp .bloop

deploy: HOST=$(firstword $(subst :, , $(DEST)))
deploy: DIR=$(lastword $(subst :, , $(DEST)))
deploy: fast-deploy
	@test -d deps  && $(RSYNC) --exclude target --copy-links deps $(DEST) || test true
	@ssh -T $(HOST) 'cd $(DIR); git pull --rebase; sbt update;'
  #rsync -av $(MAVEN_DIR)          $(HOST):.cache/coursier/v1/https/repo1.maven.org/maven2
  #eval echo `cat $(CLASSPATH_FILE)` | perl -pe 's/:/\n/g' | xargs -I{} rsync -av {} $(HOST):.cache/coursier/v1/https/repo1.maven.org/maven2

fast-deploy: DEPLOY_FILE = .deploy.java.classpath
fast-deploy: CLASSPATHS = cat $(CLASSPATH_FILE) | tr ':' '\n'
fast-deploy:
	$(CLASSPATHS) |grep -v '^/' | tr '\n' ':' > $(DEPLOY_FILE)
	scp $(DEPLOY_FILE)                $(DEST)/$(CLASSPATH_FILE)
	$(CLASSPATHS) |grep '^/' | xargs dirname | xargs -I{} $(RSYNC) {} $(DEST)/target
	#$(RSYNC) deps/*/target/scala-*    $(DEST)/target
	$(RSYNC) target/scala-*           $(DEST)/target

## for web
web/build:
	cd web/cryptoMarket/ && pnpm i && pnpm run build

web/deploy:
	rsync -avz web/cryptoMarket/dist    $(DEST)
