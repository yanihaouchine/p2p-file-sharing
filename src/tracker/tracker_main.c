#include "tracker.h"
#include "config.h"
#include "file_table.h"
#include "log.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <libgen.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <pthread.h>

#define CLIENT_BUF_SIZE 8192
#define MAX_CONSECUTIVE_ERRORS 3

typedef struct
{
    char buf[CLIENT_BUF_SIZE];
    int len;
    int error_count;
} client_state_t;

void error(const char *msg)
{
    perror(msg);
    exit(1);
}

static int g_peer_timeout = 90;

void *cleanup_thread(void *arg)
{
    (void)arg;
    while (1)
    {
        sleep(30);
        log_info("[tracker] Nettoyage des peers expirés...");
        FILE_INDEX_LOCK();
        file_index_cleanup(&file_index, g_peer_timeout);
        FILE_INDEX_UNLOCK();
    }
    return NULL;
}

static int route_command(int sd, char *line)
{
    char *cmd = line;
    if (strncmp(cmd, "< ", 2) == 0)
        cmd += 2;

    if (strncasecmp(cmd, "ANNOUNCE", 8) == 0)
        return announce_response(sd, line);
    else if (strncasecmp(cmd, "LOOK", 4) == 0)
        return look_response(sd, line);
    else if (strncasecmp(cmd, "GETFILE", 7) == 0)
        return getfile_response(sd, line);
    else if (strncasecmp(cmd, "UPDATE", 6) == 0)
        return update_response(sd, line);
    else if (strncasecmp(cmd, "STOP", 4) == 0)
        return stop_response(sd, line);
    else
    {
        log_info("Commande inconnue ignorée : '%s'", cmd);
        return 0;
    }
}

int main(int argc, char *argv[])
{
    config_t *cfg = config_get();
    config_load(cfg, "src/tracker/config.ini");
    log_set_verbosity(cfg->verbosity);
    g_peer_timeout = cfg->peer_timeout;

    int port = argc > 1 ? atoi(argv[1]) : cfg->port;
    int max_clients = cfg->max_clients;

    int server_fd, new_socket;
    int max_sd, activity;
    int i, sd;
    struct sockaddr_in address;
    fd_set readfds;

    int *client_sockets = calloc(max_clients, sizeof(int));
    client_state_t *client_states = calloc(max_clients, sizeof(client_state_t));

    if (!client_sockets || !client_states)
        error("malloc failed");

    server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0)
        error("socket failed");

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(port);

    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) < 0)
        error("bind failed");

    listen(server_fd, 5);

    pthread_t cleaner;
    pthread_create(&cleaner, NULL, cleanup_thread, NULL);
    pthread_detach(cleaner);

    log_info("Tracker en écoute sur port %d...", port);

    while (1)
    {
        FD_ZERO(&readfds);
        FD_SET(server_fd, &readfds);
        max_sd = server_fd;

        for (i = 0; i < max_clients; i++)
        {
            sd = client_sockets[i];
            if (sd > 0)
                FD_SET(sd, &readfds);
            if (sd > max_sd)
                max_sd = sd;
        }

        activity = select(max_sd + 1, &readfds, NULL, NULL, NULL);

        if (activity < 0)
            error("select error");

        // nouveau client
        if (FD_ISSET(server_fd, &readfds))
        {
            new_socket = accept(server_fd, NULL, NULL);
            if (new_socket < 0)
            {
                log_error("accept failed");
            }
            else
            {
                log_info("[tracker] Nouveau peer connecté (fd=%d)", new_socket);

                int placed = 0;
                for (i = 0; i < max_clients; i++)
                {
                    if (client_sockets[i] == 0)
                    {
                        client_sockets[i] = new_socket;
                        client_states[i].len = 0;
                        client_states[i].error_count = 0;
                        placed = 1;
                        break;
                    }
                }

                if (!placed)
                {
                    log_error("max_clients atteint, peer rejeté (fd=%d)", new_socket);
                    close(new_socket);
                }
            }
        }

        // gérer clients
        for (i = 0; i < max_clients; i++)
        {
            sd = client_sockets[i];

            if (sd <= 0 || !FD_ISSET(sd, &readfds))
                continue;

            client_state_t *st = &client_states[i];

            int space = CLIENT_BUF_SIZE - 1 - st->len;
            if (space <= 0)
            {
                log_error("Buffer overflow pour client fd=%d, fermeture", sd);
                close(sd);
                client_sockets[i] = 0;
                st->len = 0;
                continue;
            }

            int valread = read(sd, st->buf + st->len, space);

            if (valread <= 0)
            {
                log_info("[tracker] Peer déconnecté (fd=%d)", sd);
                close(sd);
                client_sockets[i] = 0;
                st->len = 0;
                continue;
            }

            st->len += valread;
            st->buf[st->len] = '\0';

            // Traiter chaque message complet (terminé par \n)
            char *start = st->buf;
            char *newline;

            while ((newline = strchr(start, '\n')) != NULL)
            {
                *newline = '\0';

                // Retirer \r si présent
                if (newline > start && *(newline - 1) == '\r')
                    *(newline - 1) = '\0';

                // Ignorer les lignes vides
                if (*start != '\0')
                {
                    int ret = route_command(sd, start);
                    if (ret != 0)
                    {
                        st->error_count++;
                        if (st->error_count >= MAX_CONSECUTIVE_ERRORS)
                        {
                            log_error("3 erreurs consécutives pour fd=%d, fermeture", sd);
                            close(sd);
                            client_sockets[i] = 0;
                            st->len = 0;
                            goto next_client;
                        }
                    }
                    else
                    {
                        st->error_count = 0;
                    }
                }

                start = newline + 1;
            }

            // Déplacer les données restantes (message incomplet) au début du buffer
            int remaining = st->len - (start - st->buf);
            if (remaining > 0)
                memmove(st->buf, start, remaining);
            st->len = remaining;

            next_client:;
        }
    }

    free(client_sockets);
    free(client_states);

    return 0;
}
