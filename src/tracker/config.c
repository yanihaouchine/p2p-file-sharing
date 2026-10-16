#include "config.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

static config_t g_config;

static void config_defaults(config_t *cfg)
{
    cfg->port         = 9041;
    cfg->max_clients  = 10;
    cfg->verbosity    = 1;
    cfg->peer_timeout = 90;
}

static char *trim(char *s)
{
    while (isspace((unsigned char)*s))
        s++;
    char *end = s + strlen(s) - 1;
    while (end > s && isspace((unsigned char)*end))
        *end-- = '\0';
    return s;
}

int config_load(config_t *cfg, const char *path)
{
    config_defaults(cfg);

    FILE *f = fopen(path, "r");
    if (!f)
    {
        fprintf(stderr, "[config] Fichier '%s' introuvable, valeurs par défaut utilisées.\n", path);
        return -1;
    }

    char line[256];
    while (fgets(line, sizeof(line), f))
    {
        char *s = trim(line);

        if (*s == '\0' || *s == '#')
            continue;

        char *eq = strchr(s, '=');
        if (!eq)
            continue;

        *eq = '\0';
        char *key = trim(s);
        char *val = trim(eq + 1);

        if (strcmp(key, "port") == 0)
            cfg->port = atoi(val);
        else if (strcmp(key, "max_clients") == 0)
            cfg->max_clients = atoi(val);
        else if (strcmp(key, "verbosity") == 0)
            cfg->verbosity = atoi(val);
        else if (strcmp(key, "peer_timeout") == 0)
            cfg->peer_timeout = atoi(val);
    }

    fclose(f);
    return 0;
}

config_t *config_get(void)
{
    return &g_config;
}
