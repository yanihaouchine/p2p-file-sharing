#ifndef MAP_H
#define MAP_H

#include <arpa/inet.h>
#include <pthread.h>
#include <time.h>

#define PIECE_SIZE 1024

extern pthread_mutex_t file_index_mutex;
#define FILE_INDEX_LOCK()   pthread_mutex_lock(&file_index_mutex)
#define FILE_INDEX_UNLOCK() pthread_mutex_unlock(&file_index_mutex)

typedef struct peer
{
    char ip[INET_ADDRSTRLEN];
    int port;
    time_t last_seen; 
} peer_t;

typedef struct file_entry
{
    char filename[256];
    char md5[33];

    int filesize;
    int piece_size;

    peer_t *peers;
    int nb_peers;
    int capacity;

    struct file_entry *next;
} file_entry_t;

typedef file_entry_t *file_index_t;

file_index_t file_index_init(void);
void file_index_add_peer(file_index_t *index,
                         const char *filename,
                         const char *md5,
                         int filesize,
                         int piece_size,
                         const char *ip,
                         int port);
file_entry_t *file_index_get(file_index_t index, const char *md5);
void file_index_remove(file_index_t *index, const char *md5);
void file_index_free(file_index_t index);
void file_index_cleanup(file_index_t *index, int timeout_seconds); 

#endif