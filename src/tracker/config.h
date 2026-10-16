#ifndef CONFIG_H
#define CONFIG_H

typedef struct {
    int port;
    int max_clients;
    int verbosity;       /* 0 = errors, 1 = info, 2 = debug */
    int peer_timeout;    /* secondes avant expiration d'un peer côté tracker */
} config_t;

/* Charge la configuration depuis un fichier .ini.
   Retourne 0 en cas de succès, -1 en cas d'erreur.
   Si le fichier est introuvable, les valeurs par défaut sont utilisées. */
int config_load(config_t *cfg, const char *path);

/* Retourne un pointeur vers la configuration globale (singleton). */
config_t *config_get(void);

#endif
