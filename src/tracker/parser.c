#define _GNU_SOURCE // strcasestr

#include "parser.h"

#include <stdio.h>
#include <string.h>
#include <strings.h>

static char *parse_keys_block(char *start,
                              char keys[][33],
                              int sizes[],
                              int *nb_keys,
                              int has_file_info)
{
    char *bracket_open = strchr(start, '[');
    if (!bracket_open)
        return NULL;

    char *bracket_close = strchr(bracket_open, ']');
    if (!bracket_close)
        return NULL;

    bracket_open++;
    *nb_keys = 0;

    char *cur = bracket_open;

    while (cur < bracket_close)
    {
        while (cur < bracket_close && *cur == ' ')
            cur++;
        if (cur >= bracket_close)
            break;

        if (has_file_info)
        {
            char word[256];
            int filesize, piecesize;
            char md5[33];

            if (sscanf(cur, "%255s %d %d %32s", word, &filesize, &piecesize, md5) != 4)
                break;

            strcpy(keys[*nb_keys], md5);
            if (sizes)
                sizes[*nb_keys] = filesize;

            (*nb_keys)++;

            for (int i = 0; i < 4; i++)
            {
                while (cur < bracket_close && *cur != ' ')
                    cur++;
                while (cur < bracket_close && *cur == ' ')
                    cur++;
            }
        }
        else
        {
            int len = 0;
            while (cur < bracket_close && *cur != ' ' && len < 32)
            {
                keys[*nb_keys][len++] = *cur;
                cur++;
            }
            keys[*nb_keys][len] = '\0';

            if (len > 0)
            {
                if (sizes)
                    sizes[*nb_keys] = -1;
                (*nb_keys)++;
            }

            /* If the key exceeded 32 chars, swallow the tail so the next
               iteration starts at a separator, not mid-token. */
            while (cur < bracket_close && *cur != ' ')
                cur++;
        }
    }

    return bracket_close + 1;
}

int parse_announce(char *buffer, int *port,
                   char seed_keys[][33], int seed_sizes[], int *nb_seed,
                   char leech_keys[][33], int *nb_leech)
{
    *nb_seed = 0;
    *nb_leech = 0;

    char *ptr = buffer;

    if (strncmp(ptr, "< ", 2) == 0)
        ptr += 2;

    if (strncasecmp(ptr, "ANNOUNCE", 8) != 0)
        return -1;
    ptr += 8;

    char *listen_ptr = strcasestr(ptr, "listen");
    if (!listen_ptr)
        return -1;
    listen_ptr += 6;

    if (sscanf(listen_ptr, " %d", port) != 1)
        return -1;

    char *seed_ptr = strcasestr(ptr, "seed");
    if (!seed_ptr)
        return -1;

    char *after_seed = parse_keys_block(seed_ptr,
                                        seed_keys,
                                        seed_sizes,
                                        nb_seed,
                                        1);
    if (!after_seed)
        return -1;

    char *leech_ptr = strcasestr(after_seed, "leech");
    if (leech_ptr)
    {
        if (!parse_keys_block(leech_ptr, leech_keys, NULL, nb_leech, 0))
            return -1;
    }

    return 0;
}

static char *parse_operator(char *s, operator_t *op)
{
    if (s[0] == '>' && s[1] == '=')
    {
        *op = OP_GTE;
        return s + 2;
    }
    if (s[0] == '<' && s[1] == '=')
    {
        *op = OP_LTE;
        return s + 2;
    }
    if (s[0] == '>')
    {
        *op = OP_GT;
        return s + 1;
    }
    if (s[0] == '<')
    {
        *op = OP_LT;
        return s + 1;
    }
    if (s[0] == '=')
    {
        *op = OP_EQ;
        return s + 1;
    }
    return NULL;
}

int parse_look(char *buffer,
               criterion_t criteria[],
               int *nb_criteria)
{
    *nb_criteria = 0;

    char *ptr = buffer;

    if (strncmp(ptr, "< ", 2) == 0)
        ptr += 2;

    if (strncasecmp(ptr, "LOOK", 4) != 0)
        return -1;
    ptr += 4;

    char *bracket_open = strchr(ptr, '[');
    if (!bracket_open)
        return -1;

    char *bracket_close = strchr(bracket_open, ']');
    if (!bracket_close)
        return -1;

    char *cur = bracket_open + 1;

    while (cur < bracket_close && *nb_criteria < MAX_CRITERIA)
    {
        while (cur < bracket_close && *cur == ' ')
            cur++;
        if (cur >= bracket_close)
            break;

        criterion_t *c = &criteria[*nb_criteria];

        int i = 0;
        while (cur < bracket_close && *cur != '=' && *cur != '>' && *cur != '<' && i < 31)
        {
            c->field[i++] = *cur++;
        }
        c->field[i] = '\0';

        if (cur >= bracket_close)
            break;

        char *after_op = parse_operator(cur, &c->op);
        if (!after_op)
            break;
        cur = after_op;

        int j = 0;
        if (*cur == '"')
        {
            cur++;
            while (cur < bracket_close && *cur != '"' && j < 63)
                c->value[j++] = *cur++;
            if (*cur == '"')
                cur++;
        }
        else
        {
            while (cur < bracket_close && *cur != ' ' && j < 63)
                c->value[j++] = *cur++;
        }
        c->value[j] = '\0';

        (*nb_criteria)++;
    }

    return 0;
}

int parse_getfile(char *buffer, char *md5)
{
    char *ptr = buffer;

    if (strncmp(ptr, "< ", 2) == 0)
        ptr += 2;

    if (strncasecmp(ptr, "GETFILE", 7) != 0)
        return -1;
    ptr += 7;

    while (*ptr == ' ')
        ptr++;

    int i = 0;
    while (*ptr && *ptr != ' ' && *ptr != '\n' && *ptr != '\r' && i < 32)
        md5[i++] = *ptr++;
    md5[i] = '\0';

    if (i == 0)
        return -1;

    return 0;
}

int parse_update(char *buffer, int *port,
                 char seed_keys[][33], int *nb_seed,
                 char leech_keys[][33], int *nb_leech)
{
    *nb_seed = 0;
    *nb_leech = 0;
    *port = 0;

    char *ptr = buffer;

    if (strncmp(ptr, "< ", 2) == 0)
        ptr += 2;

    if (strncasecmp(ptr, "UPDATE", 6) != 0)
        return -1;
    ptr += 6;

    char *seed_ptr = strcasestr(ptr, "seed");
    if (!seed_ptr)
        return -1;

    char *after_seed = parse_keys_block(seed_ptr,
                                        seed_keys,
                                        NULL,
                                        nb_seed,
                                        0);
    if (!after_seed)
        return -1;

    char *leech_ptr = strcasestr(after_seed, "leech");
    if (leech_ptr)
    {
        if (!parse_keys_block(leech_ptr,
                              leech_keys,
                              NULL,
                              nb_leech,
                              0))
            return -1;
    }

    return 0;
}

int parse_stop(char *buffer, char *md5)
{
    char *ptr = buffer;

    if (strncmp(ptr, "< ", 2) == 0)
        ptr += 2;

    if (strncasecmp(ptr, "STOP", 4) != 0)
        return -1;
    ptr += 4;

    while (*ptr == ' ')
        ptr++;

    int i = 0;
    while (*ptr && *ptr != ' ' && *ptr != '\n' && *ptr != '\r' && i < 32)
        md5[i++] = *ptr++;
    md5[i] = '\0';

    if (i == 0)
        return -1;

    return 0;
}
