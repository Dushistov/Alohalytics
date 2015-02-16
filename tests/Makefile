.PHONY: run all indent clean

CPP=g++
CPPFLAGS=-std=c++11 -Wall -W
LDFLAGS=

PWD=$(shell pwd)
SRC=$(wildcard *.cc)
BIN=$(SRC:%.cc=build/%)
OS := $(shell uname)

run: all
	./build/test_location

all: build ${BIN}

indent:
	(find . -name "*.cc") | xargs clang-format -i

clean:
	rm -rf build

build:
	mkdir -p $@

build/%: %.cc
	${CPP} ${CPPFLAGS} -o $@ $< ${MORE_SOURCES} ${LDFLAGS}