#ifndef PARSER_H
#define PARSER_H

#define MAX_CRITERIA 10

typedef enum
{
    OP_EQ,
    OP_GT,
    OP_LT,
    OP_GTE,
    OP_LTE
} operator_t;

typedef struct
{
    char field[32]; // "filesize", "filename", "date"
    operator_t op;
    char value[64]; // "1230", "movie.mp4", "2024"
} criterion_t;

int parse_announce(char *buffer, int *port,
                   char seed_keys[][33], int seed_sizes[], int *nb_seed,
                   char leech_keys[][33], int *nb_leech);

int parse_look(char *buffer,
               criterion_t criteria[],
               int *nb_criteria);

int parse_getfile(char *buffer, char *md5);

int parse_update(char *buffer, int *port,
                 char seed_keys[][33], int *nb_seed,
                 char leech_keys[][33], int *nb_leech);

int parse_stop(char *buffer, char *md5);

#endif