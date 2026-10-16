CC = gcc
CFLAGS = -Wall -Wextra -Werror -pthread

TRACKER_DIR = src/tracker

C_SRCS = \
$(TRACKER_DIR)/tracker_main.c \
$(TRACKER_DIR)/tracker.c \
$(TRACKER_DIR)/file_table.c \
$(TRACKER_DIR)/log.c \
$(TRACKER_DIR)/parser.c \
$(TRACKER_DIR)/config.c

C_OBJS = $(C_SRCS:.c=.o)

TRACKER_TARGET = tracker

JAVAC = javac
JAVA = java

SRC_DIR = src
BUILD_DIR = build

JAVA_SRCS = $(shell find $(SRC_DIR) -name "*.java")

MAIN_CLASS = src.peer.MainPeer

all: tracker java

tracker: $(C_OBJS)
	$(CC) $(CFLAGS) $(C_OBJS) -o $(TRACKER_TARGET)

%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

java:
	mkdir -p $(BUILD_DIR)
	$(JAVAC) -d $(BUILD_DIR) $(JAVA_SRCS)

run-tracker: tracker
	./$(TRACKER_TARGET)

run-peer: java
	$(JAVA) -cp $(BUILD_DIR) $(MAIN_CLASS)

clean:
	rm -f $(C_OBJS) $(TRACKER_TARGET)
	rm -rf $(BUILD_DIR)
	rm -rf $(SRC_DIR)/*/*.class

.PHONY: all tracker java clean run-tracker run-peer