#ifndef TRACKER_H
#define TRACKER_H

#include "file_table.h"

extern file_index_t file_index;

int announce_response(int client_sock, char *buffer);
int look_response(int client_sock, char *buffer);
int getfile_response(int client_sock, char *buffer);
int update_response(int client_sock, char *buffer);
int stop_response(int client_sock, char *buffer);

#endif
