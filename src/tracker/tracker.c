#define _GNU_SOURCE
#include "tracker.h"
#include "file_table.h"
#include "parser.h"
#include "log.h"

#include <string.h>
#include <stdio.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <stdlib.h>
#include <strings.h>

#define BUFFER_SIZE 8192

file_index_t file_index = NULL;
typedef struct
{
    char filename[256];
    int filesize;
    int piece_size;
    char md5[33];
} announced_file_t;

static int compare_int(int lhs, operator_t op, int rhs)
{
    switch (op)
    {
    case OP_EQ:
        return lhs == rhs;
    case OP_GT:
        return lhs > rhs;
    case OP_LT:
        return lhs < rhs;
    case OP_GTE:
        return lhs >= rhs;
    case OP_LTE:
        return lhs <= rhs;
    default:
        return 0;
    }
}

static int matches_criteria(const file_entry_t *entry,
                            criterion_t criteria[],
                            int nb_criteria)
{
    if (nb_criteria == 0)
        return 1;

    for (int i = 0; i < nb_criteria; i++)
    {
        const criterion_t *c = &criteria[i];

        if (strcasecmp(c->field, "filename") == 0)
        {
            if (c->op != OP_EQ)
                return 0;
            if (strcmp(entry->filename, c->value) != 0)
                return 0;
        }
        else if (strcasecmp(c->field, "filesize") == 0)
        {
            int wanted = atoi(c->value);
            if (!compare_int(entry->filesize, c->op, wanted))
                return 0;
        }
        else if (strcasecmp(c->field, "piecesize") == 0)
        {
            int wanted = atoi(c->value);
            if (!compare_int(entry->piece_size, c->op, wanted))
                return 0;
        }
        else if (strcasecmp(c->field, "key") == 0 || strcasecmp(c->field,
                                                                "md5") == 0)
        {
            if (c->op != OP_EQ)
                return 0;
            if (strcmp(entry->md5, c->value) != 0)
                return 0;
        }
        else
        {
            return 0;
        }
    }

    return 1;
}

static int extract_seed_files_from_announce(const char *buffer,
                                            announced_file_t files[],
                                            int *count)
{
    const char *seed_ptr = strcasestr(buffer, "seed");
    if (!seed_ptr)
        return -1;

    const char *open = strchr(seed_ptr, '[');
    if (!open)
        return -1;

    const char *close = strchr(open, ']');
    if (!close)
        return -1;

    *count = 0;
    const char *cur = open + 1;

    while (cur < close)
    {
        while (cur < close && *cur == ' ')
            cur++;

        if (cur >= close)
            break;

        announced_file_t *f = &files[*count];
        int consumed = 0;

        if (sscanf(cur, " %255s %d %d %32s%n",
                   f->filename,
                   &f->filesize,
                   &f->piece_size,
                   f->md5,
                   &consumed) != 4)
        {
            log_error("Invalid seed file entry (filename must not contain spaces)");
            return -1;
        }

        (*count)++;
        cur += consumed;
    }

    return 0;
}
static void send_response(int sock, const char *msg)
{
    send(sock, msg, strlen(msg), 0);
}

int announce_response(int client_sock, char *buffer)
{
    log_request("%s", buffer);

    int port;
    char seed_keys[50][33];
    int seed_sizes[50];
    int nb_seed = 0;

    char leech_keys[50][33];
    int nb_leech = 0;

    announced_file_t seed_files[50];
    int nb_seed_files = 0;

    if (parse_announce(buffer, &port,
                       seed_keys, seed_sizes, &nb_seed,
                       leech_keys, &nb_leech) != 0)
    {
        log_error("Invalid ANNOUNCE");
        send_response(client_sock, "error invalid_announce\n");
        return -1;
    }

    if (extract_seed_files_from_announce(buffer, seed_files, &nb_seed_files) !=
        0)
    {
        log_error("Invalid ANNOUNCE seed block");
        send_response(client_sock, "error invalid_seed_block\n");
        return -1;
    }

    struct sockaddr_in addr;
    socklen_t len = sizeof(addr);
    if (getpeername(client_sock, (struct sockaddr *)&addr, &len) != 0)
    {
        log_error("getpeername failed on fd=%d", client_sock);
        send_response(client_sock, "error internal\n");
        return -1;
    }

    char ip[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip)))
    {
        log_error("inet_ntop failed on fd=%d", client_sock);
        send_response(client_sock, "error internal\n");
        return -1;
    }

    FILE_INDEX_LOCK();
    for (int i = 0; i < nb_seed_files; i++)
    {
        file_index_add_peer(&file_index,
                            seed_files[i].filename,
                            seed_files[i].md5,
                            seed_files[i].filesize,
                            seed_files[i].piece_size,
                            ip,
                            port);
    }
    FILE_INDEX_UNLOCK();

    log_response("ok");
    send_response(client_sock, "ok\n");
    return 0;
}

int update_response(int client_sock, char *buffer)
{
    log_request("%s", buffer);

    int port;
    char seed_keys[50][33];
    int nb_seed = 0;

    char leech_keys[50][33];
    int nb_leech = 0;

    if (parse_update(buffer, &port,
                     seed_keys, &nb_seed,
                     leech_keys, &nb_leech) != 0)
    {
        log_error("Invalid UPDATE");
        send_response(client_sock, "error invalid_update\n");
        return -1;
    }

    struct sockaddr_in addr;
    socklen_t len = sizeof(addr);
    if (getpeername(client_sock, (struct sockaddr *)&addr, &len) != 0)
    {
        log_error("getpeername failed on fd=%d", client_sock);
        send_response(client_sock, "error internal\n");
        return -1;
    }

    char ip[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip)))
    {
        log_error("inet_ntop failed on fd=%d", client_sock);
        send_response(client_sock, "error internal\n");
        return -1;
    }

    FILE_INDEX_LOCK();
    for (int i = 0; i < nb_seed; i++)
    {
        file_entry_t *entry = file_index_get(file_index, seed_keys[i]);

        if (!entry)
        {
            log_error("UPDATE on unknown file %s", seed_keys[i]);
            continue;
        }

        int filesize = entry->filesize;
        int peer_port = 0;
        for (int j = 0; j < entry->nb_peers; j++)
        {
            if (strcmp(entry->peers[j].ip, ip) == 0)
            {
                peer_port = entry->peers[j].port;
                break;
            }
        }
        if (peer_port == 0)
        {
            log_error("UPDATE: port inconnu pour %s", ip);
            continue;
        }

        file_index_add_peer(&file_index,
                            entry->filename,
                            seed_keys[i],
                            filesize,
                            entry->piece_size,
                            ip,
                            peer_port);
    }
    FILE_INDEX_UNLOCK();

    log_response("ok");
    send_response(client_sock, "ok\n");

    return 0;
}

int look_response(int client_sock, char *buffer)
{
    log_request("%s", buffer);

    criterion_t criteria[MAX_CRITERIA];
    int nb_criteria = 0;

    if (parse_look(buffer, criteria, &nb_criteria) != 0)
    {
        log_error("Invalid LOOK");
        send_response(client_sock, "error invalid_look\n");
        return -1;
    }

    char response[BUFFER_SIZE];
    int offset = 0;

    offset += snprintf(response + offset, BUFFER_SIZE - offset, "list [");

    /* Réserve 2 octets pour fermer "]\n" même si on tronque la liste. */
    const int RESERVED_TAIL = 4;

    FILE_INDEX_LOCK();
    for (file_entry_t *entry = file_index; entry != NULL; entry = entry->next)
    {
        if (!matches_criteria(entry, criteria, nb_criteria))
            continue;

        if (offset >= BUFFER_SIZE - RESERVED_TAIL)
            break;

        int written = snprintf(response + offset,
                               BUFFER_SIZE - RESERVED_TAIL - offset,
                               "%s %d %d %s ",
                               entry->filename,
                               entry->filesize,
                               entry->piece_size,
                               entry->md5);

        if (written < 0 || written >= BUFFER_SIZE - RESERVED_TAIL - offset)
        {
            log_error("LOOK truncated, partial result returned");
            break;
        }
        offset += written;
    }
    FILE_INDEX_UNLOCK();

    if (offset > 0 && response[offset - 1] == ' ')
        offset--;

    snprintf(response + offset, BUFFER_SIZE - offset, "]\n");

    log_response("%s", response);
    send_response(client_sock, response);

    return 0;
}

int getfile_response(int client_sock, char *buffer)
{
    log_request("%s", buffer);

    char md5[33];

    if (parse_getfile(buffer, md5) != 0)
    {
        log_error("Invalid GETFILE");
        send_response(client_sock, "error invalid_getfile\n");
        return -1;
    }

    char response[BUFFER_SIZE];
    int offset = 0;
    const int RESERVED_TAIL = 4;

    FILE_INDEX_LOCK();
    file_entry_t *entry = file_index_get(file_index, md5);

    if (!entry)
    {
        FILE_INDEX_UNLOCK();
        /* Lookup réussi mais sans résultat -> réponse vide, pas une erreur de protocole */
        snprintf(response, BUFFER_SIZE, "peers %s []\n", md5);
        log_response("%s", response);
        send_response(client_sock, response);
        return 0;
    }

    offset += snprintf(response + offset, BUFFER_SIZE - offset, "peers %s [", md5);

    for (int i = 0; i < entry->nb_peers; i++)
    {
        if (offset >= BUFFER_SIZE - RESERVED_TAIL)
            break;

        int written = snprintf(response + offset,
                               BUFFER_SIZE - RESERVED_TAIL - offset,
                               "%s:%d ",
                               entry->peers[i].ip,
                               entry->peers[i].port);

        if (written < 0 || written >= BUFFER_SIZE - RESERVED_TAIL - offset)
        {
            log_error("GETFILE truncated, partial result returned");
            break;
        }
        offset += written;
    }
    FILE_INDEX_UNLOCK();

    if (offset > 0 && response[offset - 1] == ' ')
        offset--;

    snprintf(response + offset, BUFFER_SIZE - offset, "]\n");

    log_response("%s", response);
    send_response(client_sock, response);

    return 0;
}

int stop_response(int client_sock, char *buffer)
{
    log_request("%s", buffer);

    char md5[33];

    if (parse_stop(buffer, md5) != 0)
    {
        log_error("Invalid STOP");
        send_response(client_sock, "error invalid_stop\n");
        return -1;
    }

    FILE_INDEX_LOCK();
    file_index_remove(&file_index, md5);
    FILE_INDEX_UNLOCK();

    log_response("ok");
    send_response(client_sock, "ok\n");

    return 0;
}