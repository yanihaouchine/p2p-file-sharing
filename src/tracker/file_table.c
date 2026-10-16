#include "file_table.h"
#include "log.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define INITIAL_CAPACITY 4

pthread_mutex_t file_index_mutex = PTHREAD_MUTEX_INITIALIZER;

file_index_t file_index_init(void)
{
    return NULL;
}

static int peer_exists(file_entry_t *entry, const char *ip, int port)
{
    for (int i = 0; i < entry->nb_peers; i++)
    {
        if (strcmp(entry->peers[i].ip, ip) == 0 &&
            entry->peers[i].port == port){
                entry->peers[i].last_seen = time(NULL); 
                return 1;
            }
            
    }
    return 0;
}
static void add_peer(file_entry_t *entry, const char *ip, int port)
{
    if (peer_exists(entry, ip, port))
        return;

    if (entry->nb_peers == entry->capacity)
    {
        entry->capacity *= 2;
        entry->peers = realloc(entry->peers,
                               entry->capacity * sizeof(peer_t));
    }

    strcpy(entry->peers[entry->nb_peers].ip, ip);
    entry->peers[entry->nb_peers].port = port;
    entry->peers[entry->nb_peers].last_seen = time(NULL); 
    entry->nb_peers++;
}
void file_index_add_peer(file_index_t *index,
                         const char *filename,
                         const char *md5,
                         int filesize,
                         int piece_size,
                         const char *ip,
                         int port)
{
    file_entry_t *entry = file_index_get(*index, md5);

    if (!entry)
    {
        entry = malloc(sizeof(*entry));
        if (!entry)
            return;

        strncpy(entry->filename, filename, sizeof(entry->filename) - 1);
        entry->filename[sizeof(entry->filename) - 1] = '\0';

        strcpy(entry->md5, md5);
        entry->filesize = filesize;
        entry->piece_size = piece_size;

        entry->capacity = INITIAL_CAPACITY;
        entry->nb_peers = 0;
        entry->peers = malloc(entry->capacity * sizeof(peer_t));
        if (!entry->peers)
        {
            free(entry);
            return;
        }

        entry->next = *index;
        *index = entry;
    }
    else
    {
        if (filename && *filename)
        {
            strncpy(entry->filename, filename, sizeof(entry->filename) - 1);
            entry->filename[sizeof(entry->filename) - 1] = '\0';
        }
        entry->filesize = filesize;
        entry->piece_size = piece_size;
    }

    add_peer(entry, ip, port);
}

file_entry_t *file_index_get(file_index_t index, const char *md5)
{
    while (index)
    {
        if (strcmp(index->md5, md5) == 0)
            return index;
        index = index->next;
    }
    return NULL;
}

void file_index_remove(file_index_t *index, const char *md5)
{
    file_entry_t *curr = *index;
    file_entry_t *prev = NULL;

    while (curr)
    {
        if (strcmp(curr->md5, md5) == 0)
        {
            if (prev)
                prev->next = curr->next;
            else
                *index = curr->next;

            free(curr->peers);
            free(curr);
            return;
        }

        prev = curr;
        curr = curr->next;
    }
}
void file_index_free(file_index_t index)
{
    file_entry_t *tmp;

    while (index)
    {
        tmp = index;
        index = index->next;

        free(tmp->peers);
        free(tmp);
    }
}
void file_index_cleanup(file_index_t *index, int timeout_seconds)
{
    time_t now = time(NULL);
    file_entry_t *entry = *index;
    file_entry_t *prev = NULL;

    while (entry != NULL)
    {
        // Supprimer les peers expirés
        for (int i = 0; i < entry->nb_peers; )
        {
            if (difftime(now, entry->peers[i].last_seen) > timeout_seconds)
            {
                log_info("[tracker] Peer %s:%d expiré, supprimé de %s",
                         entry->peers[i].ip, entry->peers[i].port, entry->filename);
                for (int j = i; j < entry->nb_peers - 1; j++)
                    entry->peers[j] = entry->peers[j + 1];
                entry->nb_peers--;
            }
            else
            {
                i++;
            }
        }

        // Si plus aucun peer -> supprimer le fichier
        file_entry_t *next = entry->next;
        if (entry->nb_peers == 0)
        {
            log_info("[tracker] Fichier %s sans seeder, supprimé", entry->filename);
            if (prev == NULL)
                *index = next;
            else
                prev->next = next;
            free(entry->peers);
            free(entry);
        }
        else
        {
            prev = entry;
        }
        entry = next;
    }
}