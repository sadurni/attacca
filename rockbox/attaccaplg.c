/***************************************************************************
 * Attacca for Rockbox
 *
 * Generates chained playlists on the player (the end of each song flows into
 * the start of the next one) from the library exported by Attacca Desktop
 * (attaccaplg.bpcr2), with the same rules as Attacca and Attacca Desktop:
 *  - from the song that is playing: the closest songs (same mood and sound
 *    family if asked), 10 % extra candidates, chained from it; the filters can
 *    be fitted around the song (10 times the songs asked for, at least twice);
 *  - by filters (mood, sound family, genre tag, artist, years, BPM, energy,
 *    positivity, voice, only included);
 *  - reordering the current playlist;
 *  - other lists: energy curves, a sampler of the sound families, the opposite of a song, a
 *    list for a set time, albums like the current one, a bridge from one song to another (either
 *    way) and a list from a random song of a mood;
 * avoiding repeating an artist within the last N songs. And Attacca's automatic crossfade: in the
 * background, each join set up from how the songs start and end (Rockbox's own crossfade
 * settings, changed before every join).
 *
 * Integer arithmetic only (no FPU on most players). The library is read from
 * disk on demand: it does not need to fit in memory.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 ****************************************************************************/

#include "plugin.h"
#include "lib/configfile.h"

/* ======================= Library format (.bpcr2) ======================= */
/* See PlayerDb.kt in Attacca Desktop for the full description. */

#define MAGIC "BPCR2\0\0\0"
#define HEADER_SIZE 64
#define NONE16 0xFFFFu
#define NONE32 0xFFFFFFFFu
#define N_DICTS 4
enum { D_ARTIST, D_GENRE, D_MOOD, D_FAMILY };
#define VEC 58   /* sound profile's main directions (32) and timbre (26) */
#define F_INCLUDED 1
#define F_HEAD 2
#define F_TAIL 4

struct record {
    uint32_t title, path, album, hpath, htail, edge;
    uint16_t artist, genre, duration, year, bpm100;
    uint16_t energy, positivity, voice;     /* percentiles x 10000 */
    uint8_t mood, family, flags;
    uint8_t mood2;                          /* second mood + 1, for a song between two (0: none) */
    int16_t vec[VEC];
};
typedef char record_size_check[sizeof(struct record) == 160 ? 1 : -1];

struct edge {
    int16_t chroma[12];   /* centred, length 16384 */
    int16_t bands[16];    /* dB x 100 */
    int16_t level;        /* dB x 100 */
    int16_t activity;     /* ln(flux + 0.05) x 1000 */
    int16_t timbre[13];   /* timbre coefficients x 100 */
    int16_t tempo;        /* log2(BPM) x 1000, 0: none */
    int16_t pulse;        /* beat clarity x 10000 */
    int16_t key;          /* 0-11 major, 12-23 minor; -1: described before timbre and tempo */
    int16_t key_clarity;  /* x 10000 */
};
typedef char edge_size_check[sizeof(struct edge) == 94 ? 1 : -1];

static struct {
    uint32_t n, recsize, dictoff, recoff, edgeoff, stroff, strsize, created, base;
    uint16_t spacing, edgesec, yearmin, yearmax, bpm100min, bpm100max;
} H;

static int dbfd = -1;
static char dbpath[MAX_PATH];
static char base[MAX_PATH];
static uint32_t dict_n[N_DICTS];
static uint32_t *dict_off[N_DICTS];

/* ======================= Texts (English, Spanish and the others) ======================= */

enum {
    S_TITLE, S_FROM_CURRENT, S_BY_FILTERS, S_REORDER, S_CATEGORY, S_MORE, S_FIT,
    S_SAME_MOOD, S_SAME_FAMILY, S_COUNT, S_SPACING, S_AFTER, S_PLAY, S_SAVE_ONLY,
    S_START_CURRENT, S_INFO, S_ANY, S_MOOD, S_FAMILY, S_GENRE, S_ARTIST, S_CLEAR,
    S_YEAR_FROM, S_YEAR_TO, S_BPM_MIN, S_BPM_MAX, S_ENERGY_MIN, S_ENERGY_MAX,
    S_POS_MIN, S_POS_MAX, S_VOICE, S_VOICE_VOICE, S_VOICE_INSTR, S_ONLY_INC,
    S_YES, S_NO, S_RESET, S_OFF, S_NO_DB, S_BAD_DB, S_NOTHING_PLAYING, S_NOT_IN_DB,
    S_NO_SONGS, S_CHAINING, S_READING, S_CANCELLED, S_NO_MEMORY, S_SAVED,
    S_WRITE_FAILED, S_INFO_FMT, S_EMPTY_PLAYLIST, S_REORDER_FMT, S_FIT_FMT,
    S_FIT_SHORT, S_AUDIO_BUFFER, S_MIN_MINUTES, S_MAX_MINUTES,
    S_OTHER, S_RISE_FALL, S_WARM_UP, S_WIND_DOWN, S_SAMPLER, S_OPPOSITE, S_FOR_TIME, S_ALBUMS,
    S_BRIDGE, S_MINUTES, S_CURVE_START, S_PICK_ARTIST, S_PICK_SONG,
    S_BRIDGE_TO, S_RANDOM, S_XF, S_XF_LEN, S_XF_OLD_DB, S_XF_ON, S_XF_OFF, S_XF_JUMP,
    S_KEEP_ALBUMS, S_LIBRARY, S_SET, S_EVERYTHING, S_SKIP_FADE,
    S_COUNT_
};

static const char *const texts_en[S_COUNT_] = {
    "Attacca", "From the current song", "Generate by filters", "Reorder current playlist",
    "Mood, family, genre, artist", "More filters", "Fit the filters to the current song",
    "Same mood", "Same sound family", "Songs", "Artist spacing", "When done", "Play", "Save only",
    "Start with current song", "Library info", "(any)",
    "Mood", "Sound family", "Genre", "Artist", "Clear these filters",
    "Year from", "Year to", "Minimum BPM", "Maximum BPM", "Energy from", "Energy to",
    "Positivity from", "Positivity to", "Voice", "With voice", "Instrumental", "Only included",
    "Yes", "No", "Reset these filters", "off",
    "No library: copy attaccaplg.bpcr2 to the root of the player",
    "Not an Attacca Desktop library (.bpcr2)",
    "Nothing is playing", "The current song is not in the library",
    "No songs match those filters", "Chaining", "Reading", "Cancelled",
    "Not enough memory", "Saved %s (%d songs, link %d%%)", "Could not write %s",
    "%lu songs · %s", "The current playlist is empty", "%d of %d songs found",
    "%d songs to choose from", "Only %d songs, even with the filters open",
    "Stopping playback to use its memory", "Minutes from", "Minutes to",
    "Other lists", "Energy up and down", "Warming up", "Winding down", "Sampler of sound families",
    "The opposite of the current song", "For a set time from the current song",
    "Albums like the current one", "Bridge from the current song to another", "Minutes",
    "Curves start with current song", "Artist of the last song", "Last song",
    "Bridge from another song to the current one", "From a random song of a mood",
    "Automatic crossfade", "Crossfade length",
    "Export the library again from Attacca for the automatic crossfade",
    "Automatic crossfade on: it goes on in the background",
    "Automatic crossfade off: your own crossfade is back",
    "Jump over long silences",
    "Keep albums whole",
    "Library", "Music", "Everything",
    "Fade on skips",
};

static const char *const texts_es[S_COUNT_] = {
    "Attacca", "Desde la canción actual", "Generar por filtros", "Reordenar la lista actual",
    "Ánimo, familia, género, artista", "Más filtros", "Ajustar los filtros a la canción actual",
    "Mismo ánimo", "Misma familia de sonido", "Canciones", "Separación de artista", "Al terminar",
    "Reproducir", "Solo guardar", "Empezar por la canción actual", "Info de la biblioteca",
    "(cualquiera)",
    "Ánimo", "Familia de sonido", "Género", "Artista", "Quitar estos filtros",
    "Año desde", "Año hasta", "BPM mínimo", "BPM máximo", "Energía desde", "Energía hasta",
    "Positividad desde", "Positividad hasta", "Voz", "Con voz", "Instrumental", "Solo incluidas",
    "Sí", "No", "Restablecer estos filtros", "no",
    "Sin biblioteca: copia attaccaplg.bpcr2 a la raíz del reproductor",
    "No es una biblioteca de Attacca Desktop (.bpcr2)",
    "No suena nada", "La canción actual no está en la biblioteca",
    "Ninguna canción cumple esos filtros", "Encadenando", "Leyendo", "Cancelado",
    "Memoria insuficiente", "Guardada %s (%d canciones, enlace %d%%)", "No se pudo escribir %s",
    "%lu canciones · %s", "La lista actual está vacía", "%d de %d canciones encontradas",
    "%d canciones entre las que elegir", "Solo %d canciones, aun con los filtros abiertos",
    "Se detiene la reproducción para usar su memoria", "Minutos desde", "Minutos hasta",
    "Otras listas", "Energía que sube y baja", "Para ir subiendo", "Para ir bajando",
    "Muestrario de familias de sonido", "Lo contrario de la canción actual",
    "Para un tiempo desde la canción actual", "Álbumes como el actual",
    "Puente de la canción actual a otra", "Minutos",
    "Las curvas empiezan por la canción actual", "Artista de la última canción", "Última canción",
    "Puente de otra canción a la actual", "Desde una canción al azar de un ánimo",
    "Fundido automático", "Duración del fundido",
    "Exporta otra vez la biblioteca desde Attacca para el fundido automático",
    "Fundido automático activado: sigue en segundo plano",
    "Fundido automático desactivado: vuelve tu propio fundido",
    "Saltar los silencios largos",
    "Álbumes enteros, sin fundidos",
    "Biblioteca", "Música", "Todo",
    "Fundir al saltar",
};

/* BEGIN generated texts: the other languages of Attacca (dev tooling writes this part) */
static const char *const texts_ca[S_COUNT_] = {
    "Attacca",
    "Des de la cançó actual",
    "Genera per filtres",
    "Reordena la llista actual",
    "Ànim, família, gènere, artista",
    "Més filtres",
    "Ajusta els filtres a la cançó actual",
    "Mateix estat d'ànim",
    "Mateixa família de so",
    "Cançons",
    "Separació d'artista",
    "En acabar",
    "Reprodueix",
    "Només desa",
    "Comença per la cançó actual",
    "Info de la biblioteca",
    "(qualsevol)",
    "Estat d'ànim",
    "Família de so",
    "Gènere",
    "Artista",
    "Treu aquests filtres",
    "Any des de",
    "Any fins a",
    "BPM mínim",
    "BPM màxim",
    "Energia des de",
    "Energia fins a",
    "Positivitat des de",
    "Positivitat fins a",
    "Veu",
    "Amb veu",
    "Instrumental",
    "Només incloses",
    "Sí",
    "No",
    "Restableix aquests filtres",
    "no",
    "Sense biblioteca: copia attaccaplg.bpcr2 a l'arrel del reproductor",
    "No és una biblioteca d'Attacca Desktop (.bpcr2)",
    "No sona res",
    "La cançó actual no és a la biblioteca",
    "Cap cançó compleix aquests filtres",
    "Encadenant",
    "Llegint",
    "Cancel·lat",
    "Memòria insuficient",
    "Desada %s (%d cançons, enllaç %d%%)",
    "No s'ha pogut escriure %s",
    "%lu cançons · %s",
    "La llista actual és buida",
    "%d de %d cançons trobades",
    "%d cançons entre les quals triar",
    "Només %d cançons, fins i tot amb els filtres oberts",
    "S'atura la reproducció per fer servir la seva memòria",
    "Minuts des de",
    "Minuts fins a",
    "Altres llistes",
    "Energia que puja i baixa",
    "Per anar pujant",
    "Per anar baixant",
    "Mostrari de famílies de so",
    "El contrari de la cançó actual",
    "Per a un temps des de la cançó actual",
    "Àlbums com l'actual",
    "Pont de la cançó actual a una altra",
    "Minuts",
    "Les corbes comencen per la cançó actual",
    "Artista de l'última cançó",
    "Última cançó",
    "Pont d'una altra cançó a l'actual",
    "Des d'una cançó a l'atzar d'un estat d'ànim",
    "Crossfade automàtic",
    "Durada del crossfade",
    "Torna a exportar la biblioteca des d'Attacca per al crossfade automàtic",
    "Crossfade automàtic activat: continua en segon pla",
    "Crossfade automàtic desactivat: torna el teu crossfade",
    "Salta els silencis llargs",
    "Àlbums sencers, sense crossfade",
    "Biblioteca", "Música", "Tot",
    "Fon en saltar",
};
static const char *const texts_gl[S_COUNT_] = {
    "Attacca",
    "Desde a canción actual",
    "Xerar por filtros",
    "Reordenar a lista actual",
    "Ánimo, familia, xénero, artista",
    "Máis filtros",
    "Axustar os filtros á canción actual",
    "Mesmo estado de ánimo",
    "Mesma familia de son",
    "Cancións",
    "Separación de artista",
    "Ao rematar",
    "Reproducir",
    "Só gardar",
    "Comezar pola canción actual",
    "Info da biblioteca",
    "(calquera)",
    "Estado de ánimo",
    "Familia de son",
    "Xénero",
    "Artista",
    "Quitar estes filtros",
    "Ano desde",
    "Ano ata",
    "BPM mínimo",
    "BPM máximo",
    "Enerxía desde",
    "Enerxía ata",
    "Positividade desde",
    "Positividade ata",
    "Voz",
    "Con voz",
    "Instrumental",
    "Só as incluídas",
    "Si",
    "Non",
    "Restablecer estes filtros",
    "non",
    "Sen biblioteca: copia attaccaplg.bpcr2 á raíz do reprodutor",
    "Non é unha biblioteca de Attacca Desktop (.bpcr2)",
    "Non soa nada",
    "A canción actual non está na biblioteca",
    "Ningunha canción cumpre eses filtros",
    "Encadeando",
    "Lendo",
    "Cancelado",
    "Memoria insuficiente",
    "Gardada %s (%d cancións, ligazón %d%%)",
    "Non se puido escribir %s",
    "%lu cancións · %s",
    "A lista actual está baleira",
    "%d de %d cancións atopadas",
    "%d cancións entre as que escoller",
    "Só %d cancións, aínda cos filtros abertos",
    "Detense a reprodución para usar a súa memoria",
    "Minutos desde",
    "Minutos ata",
    "Outras listas",
    "Enerxía que sobe e baixa",
    "Para ir subindo",
    "Para ir baixando",
    "Mostrario de familias de son",
    "O contrario da canción actual",
    "Para un tempo desde a canción actual",
    "Álbums como o actual",
    "Ponte da canción actual a outra",
    "Minutos",
    "As curvas comezan pola canción actual",
    "Artista da última canción",
    "Última canción",
    "Ponte doutra canción á actual",
    "Desde unha canción ao chou dun estado de ánimo",
    "Crossfade automático",
    "Duración do crossfade",
    "Exporta outra vez a biblioteca desde Attacca para o crossfade automático",
    "Crossfade automático activado: segue en segundo plano",
    "Crossfade automático desactivado: volve o teu propio crossfade",
    "Saltar os silencios longos",
    "Álbums enteiros, sen crossfade",
    "Biblioteca", "Música", "Todo",
    "Fundir ao saltar",
};
static const char *const texts_eu[S_COUNT_] = {
    "Attacca",
    "Uneko abestitik",
    "Sortu iragazkien arabera",
    "Berrordenatu uneko zerrenda",
    "Aldartea, familia, generoa, artista",
    "Iragazki gehiago",
    "Doitu iragazkiak uneko abestira",
    "Aldarte bera",
    "Soinu-familia bera",
    "Abestiak",
    "Artisten arteko tartea",
    "Amaitzean",
    "Erreproduzitu",
    "Gorde soilik",
    "Hasi uneko abestiarekin",
    "Liburutegiaren info",
    "(edozein)",
    "Aldartea",
    "Soinu-familia",
    "Generoa",
    "Artista",
    "Kendu iragazki hauek",
    "Urtetik",
    "Urtera arte",
    "Gutxieneko BPM",
    "Gehieneko BPM",
    "Energia hemendik",
    "Energia honaino",
    "Positibotasuna hemendik",
    "Positibotasuna honaino",
    "Ahotsa",
    "Ahotsarekin",
    "Instrumentala",
    "Sartutakoak soilik",
    "Bai",
    "Ez",
    "Berrezarri iragazki hauek",
    "ez",
    "Liburutegirik ez: kopiatu attaccaplg.bpcr2 erreproduzigailuaren erroan",
    "Ez da Attacca Desktop liburutegi bat (.bpcr2)",
    "Ez da ezer entzuten",
    "Uneko abestia ez dago liburutegian",
    "Ez dago iragazki horiek betetzen dituen abestirik",
    "Kateatzen",
    "Irakurtzen",
    "Bertan behera utzita",
    "Ez dago memoria nahikorik",
    "%s gordeta (%d abesti, lotura %d%%)",
    "Ezin izan da %s idatzi",
    "%lu abesti · %s",
    "Uneko zerrenda hutsik dago",
    "%d / %d abesti aurkituta",
    "%d abesti aukeratzeko",
    "%d abesti bakarrik, iragazkiak irekita ere",
    "Erreprodukzioa gelditzen, haren memoria erabiltzeko",
    "Minutuak hemendik",
    "Minutuak honaino",
    "Beste zerrendak",
    "Igo eta jaisten den energia",
    "Pixkanaka igotzeko",
    "Pixkanaka jaisteko",
    "Soinu-familien lagina",
    "Uneko abestiaren kontrakoa",
    "Denbora jakin baterako, uneko abestitik",
    "Unekoa bezalako albumak",
    "Zubia uneko abestitik beste batera",
    "Minutuak",
    "Kurbak uneko abestiarekin hasten dira",
    "Azken abestiaren artista",
    "Azken abestia",
    "Zubia beste abesti batetik unekora",
    "Aldarte bateko ausazko abesti batetik",
    "Crossfade automatikoa",
    "Crossfadearen iraupena",
    "Esportatu berriro liburutegia Attaccatik crossfade automatikorako",
    "Crossfade automatikoa aktibatuta: atzeko planoan jarraitzen du",
    "Crossfade automatikoa desaktibatuta: zure crossfadea itzuli da",
    "Saltatu isilune luzeak",
    "Albumak osorik, crossfaderik gabe",
    "Liburutegia", "Musika", "Dena",
    "Urtu saltatzean",
};
static const char *const texts_pt[S_COUNT_] = {
    "Attacca",
    "A partir da canção atual",
    "Gerar por filtros",
    "Reordenar a lista atual",
    "Estado, família, género, artista",
    "Mais filtros",
    "Ajustar os filtros à canção atual",
    "Mesmo estado de espírito",
    "Mesma família sonora",
    "Canções",
    "Separação de artista",
    "No fim",
    "Reproduzir",
    "Só guardar",
    "Começar pela canção atual",
    "Info da biblioteca",
    "(qualquer)",
    "Estado de espírito",
    "Família sonora",
    "Género",
    "Artista",
    "Limpar estes filtros",
    "Ano desde",
    "Ano até",
    "BPM mínimo",
    "BPM máximo",
    "Energia desde",
    "Energia até",
    "Positividade desde",
    "Positividade até",
    "Voz",
    "Com voz",
    "Instrumental",
    "Só incluídas",
    "Sim",
    "Não",
    "Repor estes filtros",
    "não",
    "Sem biblioteca: copia attaccaplg.bpcr2 para a raiz do leitor",
    "Não é uma biblioteca do Attacca Desktop (.bpcr2)",
    "Nada a tocar",
    "A canção atual não está na biblioteca",
    "Nenhuma canção cumpre esses filtros",
    "A encadear",
    "A ler",
    "Cancelado",
    "Memória insuficiente",
    "Guardada %s (%d canções, ligação %d%%)",
    "Não foi possível escrever %s",
    "%lu canções · %s",
    "A lista atual está vazia",
    "%d de %d canções encontradas",
    "%d canções por onde escolher",
    "Só %d canções, mesmo com os filtros abertos",
    "A parar a reprodução para usar a sua memória",
    "Minutos desde",
    "Minutos até",
    "Outras listas",
    "Energia que sobe e desce",
    "Para aquecer",
    "Para acalmar",
    "Amostra de famílias sonoras",
    "O contrário da canção atual",
    "Para um tempo a partir da canção atual",
    "Álbuns como o atual",
    "Ponte da canção atual para outra",
    "Minutos",
    "As curvas começam pela canção atual",
    "Artista da última canção",
    "Última canção",
    "Ponte de outra canção para a atual",
    "De uma canção aleatória de um estado",
    "Crossfade automático",
    "Duração do crossfade",
    "Exporta outra vez a biblioteca a partir do Attacca para o crossfade automático",
    "Crossfade automático ativado: continua em segundo plano",
    "Crossfade automático desativado: volta o teu crossfade",
    "Saltar silêncios longos",
    "Álbuns inteiros, sem crossfade",
    "Biblioteca", "Música", "Tudo",
    "Fundir ao saltar",
};
static const char *const texts_pt_br[S_COUNT_] = {
    "Attacca",
    "A partir da música atual",
    "Gerar por filtros",
    "Reordenar a lista atual",
    "Humor, família, gênero, artista",
    "Mais filtros",
    "Ajustar os filtros à música atual",
    "Mesmo humor",
    "Mesma família sonora",
    "Músicas",
    "Distância entre artistas",
    "Ao terminar",
    "Tocar",
    "Só salvar",
    "Começar pela música atual",
    "Info da biblioteca",
    "(qualquer)",
    "Humor",
    "Família sonora",
    "Gênero",
    "Artista",
    "Limpar estes filtros",
    "Ano de",
    "Ano até",
    "BPM mínimo",
    "BPM máximo",
    "Energia de",
    "Energia até",
    "Positividade de",
    "Positividade até",
    "Voz",
    "Com voz",
    "Instrumental",
    "Só incluídas",
    "Sim",
    "Não",
    "Redefinir estes filtros",
    "não",
    "Sem biblioteca: copie attaccaplg.bpcr2 para a raiz do player",
    "Não é uma biblioteca do Attacca Desktop (.bpcr2)",
    "Nada tocando",
    "A música atual não está na biblioteca",
    "Nenhuma música passa por esses filtros",
    "Encadeando",
    "Lendo",
    "Cancelado",
    "Memória insuficiente",
    "%s salva (%d músicas, ligação %d%%)",
    "Não foi possível gravar %s",
    "%lu músicas · %s",
    "A lista atual está vazia",
    "%d de %d músicas encontradas",
    "%d músicas para escolher",
    "Só %d músicas, mesmo com os filtros abertos",
    "Parando a reprodução para usar a memória dela",
    "Minutos de",
    "Minutos até",
    "Outras listas",
    "Energia que sobe e desce",
    "Para aquecer",
    "Para relaxar",
    "Amostra de famílias sonoras",
    "O contrário da música atual",
    "Por um tempo, a partir da música atual",
    "Álbuns como o atual",
    "Ponte da música atual a outra",
    "Minutos",
    "Curvas começam pela música atual",
    "Artista da última música",
    "Última música",
    "Ponte de outra música até a atual",
    "De uma música ao acaso de um humor",
    "Crossfade automático",
    "Duração do crossfade",
    "Exporte de novo a biblioteca do Attacca para o crossfade automático",
    "Crossfade automático ligado: continua em segundo plano",
    "Crossfade automático desligado: seu crossfade voltou",
    "Pular silêncios longos",
    "Álbuns inteiros, sem crossfade",
    "Biblioteca", "Música", "Tudo",
    "Fundir ao pular",
};
static const char *const texts_fr[S_COUNT_] = {
    "Attacca",
    "Depuis le morceau actuel",
    "Générer par filtres",
    "Réordonner la liste actuelle",
    "Ambiance, famille, genre, artiste",
    "Plus de filtres",
    "Ajuster les filtres au morceau actuel",
    "Même ambiance",
    "Même famille sonore",
    "Morceaux",
    "Écart entre artistes",
    "À la fin",
    "Lire",
    "Enregistrer seulement",
    "Commencer par le morceau actuel",
    "Infos bibliothèque",
    "(tous)",
    "Ambiance",
    "Famille sonore",
    "Genre",
    "Artiste",
    "Effacer ces filtres",
    "Année de",
    "Année à",
    "BPM minimum",
    "BPM maximum",
    "Énergie de",
    "Énergie à",
    "Positivité de",
    "Positivité à",
    "Voix",
    "Avec voix",
    "Instrumental",
    "Inclus seulement",
    "Oui",
    "Non",
    "Réinitialiser ces filtres",
    "non",
    "Pas de bibliothèque : copiez attaccaplg.bpcr2 à la racine du baladeur",
    "Pas une bibliothèque Attacca Desktop (.bpcr2)",
    "Rien en lecture",
    "Le morceau actuel n’est pas dans la bibliothèque",
    "Aucun morceau ne passe ces filtres",
    "Enchaînement",
    "Lecture",
    "Annulé",
    "Mémoire insuffisante",
    "%s enregistrée (%d morceaux, lien %d%%)",
    "Impossible d’écrire %s",
    "%lu morceaux · %s",
    "La liste actuelle est vide",
    "%d sur %d morceaux trouvés",
    "%d morceaux au choix",
    "Seulement %d morceaux, même filtres ouverts",
    "Arrêt de la lecture pour utiliser sa mémoire",
    "Minutes de",
    "Minutes à",
    "Autres listes",
    "Énergie qui monte et descend",
    "Montée en douceur",
    "Descente en douceur",
    "Échantillon des familles sonores",
    "L’opposé du morceau actuel",
    "Durée donnée depuis le morceau actuel",
    "Albums comme l’actuel",
    "Pont du morceau actuel vers un autre",
    "Minutes",
    "Courbes depuis le morceau actuel",
    "Artiste du dernier morceau",
    "Dernier morceau",
    "Pont d’un autre morceau vers l’actuel",
    "Depuis un morceau au hasard d’une ambiance",
    "Fondu enchaîné automatique",
    "Durée du fondu enchaîné",
    "Réexportez la bibliothèque depuis Attacca pour le fondu enchaîné automatique",
    "Fondu enchaîné automatique activé : il continue en arrière-plan",
    "Fondu enchaîné automatique désactivé : votre fondu enchaîné revient",
    "Sauter les longs silences",
    "Albums entiers, sans fondu",
    "Bibliothèque", "Musique", "Tout",
    "Fondu en sautant",
};
static const char *const texts_it[S_COUNT_] = {
    "Attacca",
    "Dal brano attuale",
    "Genera per filtri",
    "Riordina la lista attuale",
    "Atmosfera, famiglia, genere, artista",
    "Altri filtri",
    "Adatta i filtri al brano attuale",
    "Stessa atmosfera",
    "Stessa famiglia sonora",
    "Brani",
    "Distanza tra artisti",
    "Al termine",
    "Riproduci",
    "Solo salva",
    "Inizia dal brano attuale",
    "Info libreria",
    "(qualsiasi)",
    "Atmosfera",
    "Famiglia sonora",
    "Genere",
    "Artista",
    "Azzera questi filtri",
    "Anno da",
    "Anno a",
    "BPM minimo",
    "BPM massimo",
    "Energia da",
    "Energia a",
    "Positività da",
    "Positività a",
    "Voce",
    "Con voce",
    "Strumentale",
    "Solo inclusi",
    "Sì",
    "No",
    "Ripristina questi filtri",
    "no",
    "Nessuna libreria: copia attaccaplg.bpcr2 nella radice del lettore",
    "Non è una libreria di Attacca Desktop (.bpcr2)",
    "Niente in riproduzione",
    "Il brano attuale non è nella libreria",
    "Nessun brano passa questi filtri",
    "Concatenamento",
    "Lettura",
    "Annullato",
    "Memoria insufficiente",
    "%s salvata (%d brani, collegamento %d%%)",
    "Impossibile scrivere %s",
    "%lu brani · %s",
    "La lista attuale è vuota",
    "%d di %d brani trovati",
    "%d brani tra cui scegliere",
    "Solo %d brani, anche con i filtri aperti",
    "Arresto della riproduzione per usarne la memoria",
    "Minuti da",
    "Minuti a",
    "Altre liste",
    "Energia che sale e scende",
    "Per scaldarsi",
    "Per rilassarsi",
    "Campionario di famiglie sonore",
    "L’opposto del brano attuale",
    "Per un tempo dato dal brano attuale",
    "Album come quello attuale",
    "Ponte dal brano attuale a un altro",
    "Minuti",
    "Le curve iniziano dal brano attuale",
    "Artista dell’ultimo brano",
    "Ultimo brano",
    "Ponte da un altro brano a quello attuale",
    "Da un brano a caso di un’atmosfera",
    "Dissolvenza automatica",
    "Durata della dissolvenza",
    "Esporta di nuovo la libreria da Attacca per la dissolvenza automatica",
    "Dissolvenza automatica attiva: continua in secondo piano",
    "Dissolvenza automatica disattivata: torna la tua dissolvenza",
    "Salta i silenzi lunghi",
    "Album interi, senza dissolvenza",
    "Libreria", "Musica", "Tutto",
    "Dissolvenza nei salti",
};
static const char *const texts_de[S_COUNT_] = {
    "Attacca",
    "Ab dem aktuellen Titel",
    "Nach Filtern erzeugen",
    "Aktuelle Liste umsortieren",
    "Stimmung, Familie, Genre, Interpret",
    "Weitere Filter",
    "Filter an den aktuellen Titel anpassen",
    "Gleiche Stimmung",
    "Gleiche Klangfamilie",
    "Titel",
    "Interpreten-Abstand",
    "Danach",
    "Abspielen",
    "Nur speichern",
    "Mit aktuellem Titel beginnen",
    "Bibliotheksinfo",
    "(beliebig)",
    "Stimmung",
    "Klangfamilie",
    "Genre",
    "Interpret",
    "Diese Filter löschen",
    "Jahr von",
    "Jahr bis",
    "BPM minimal",
    "BPM maximal",
    "Energie von",
    "Energie bis",
    "Positivität von",
    "Positivität bis",
    "Gesang",
    "Mit Gesang",
    "Instrumental",
    "Nur eingeschlossene",
    "Ja",
    "Nein",
    "Diese Filter zurücksetzen",
    "aus",
    "Keine Bibliothek: kopiere attaccaplg.bpcr2 ins Stammverzeichnis des Players",
    "Keine Attacca-Desktop-Bibliothek (.bpcr2)",
    "Es läuft nichts",
    "Der aktuelle Titel ist nicht in der Bibliothek",
    "Kein Titel passt zu diesen Filtern",
    "Verkettung",
    "Lesen",
    "Abgebrochen",
    "Nicht genug Speicher",
    "%s gespeichert (%d Titel, Verbindung %d%%)",
    "%s konnte nicht geschrieben werden",
    "%lu Titel · %s",
    "Die aktuelle Liste ist leer",
    "%d von %d Titeln gefunden",
    "%d Titel zur Auswahl",
    "Nur %d Titel, selbst mit offenen Filtern",
    "Wiedergabe wird gestoppt, um ihren Speicher zu nutzen",
    "Minuten von",
    "Minuten bis",
    "Weitere Listen",
    "Energie auf und ab",
    "Langsam steigern",
    "Langsam runterkommen",
    "Querschnitt der Klangfamilien",
    "Das Gegenteil des aktuellen Titels",
    "Feste Zeit ab dem aktuellen Titel",
    "Alben wie das aktuelle",
    "Brücke vom aktuellen Titel zu einem anderen",
    "Minuten",
    "Kurven beginnen mit aktuellem Titel",
    "Interpret des letzten Titels",
    "Letzter Titel",
    "Brücke von einem anderen Titel zum aktuellen",
    "Ab einem zufälligen Titel einer Stimmung",
    "Automatische Überblendung",
    "Länge der Überblendung",
    "Exportiere die Bibliothek erneut aus Attacca für die automatische Überblendung",
    "Automatische Überblendung an: läuft im Hintergrund weiter",
    "Automatische Überblendung aus: deine eigene Überblendung ist zurück",
    "Lange Stille überspringen",
    "Alben am Stück, ohne Überblendung",
    "Bibliothek", "Musik", "Alles",
    "Beim Springen überblenden",
};
static const char *const texts_nl[S_COUNT_] = {
    "Attacca",
    "Vanaf het huidige nummer",
    "Maken met filters",
    "Huidige lijst herordenen",
    "Stemming, familie, genre, artiest",
    "Meer filters",
    "Filters op huidig nummer afstemmen",
    "Zelfde stemming",
    "Zelfde klankfamilie",
    "Nummers",
    "Afstand tussen artiest",
    "Daarna",
    "Afspelen",
    "Alleen opslaan",
    "Begin met huidig nummer",
    "Bibliotheekinfo",
    "(alle)",
    "Stemming",
    "Klankfamilie",
    "Genre",
    "Artiest",
    "Deze filters wissen",
    "Jaar vanaf",
    "Jaar tot",
    "Minimale BPM",
    "Maximale BPM",
    "Energie vanaf",
    "Energie tot",
    "Positiviteit vanaf",
    "Positiviteit tot",
    "Stem",
    "Met zang",
    "Instrumentaal",
    "Alleen ingesloten",
    "Ja",
    "Nee",
    "Deze filters terugzetten",
    "uit",
    "Geen bibliotheek: kopieer attaccaplg.bpcr2 naar de hoofdmap van de speler",
    "Geen Attacca Desktop-bibliotheek (.bpcr2)",
    "Er speelt niets",
    "Het huidige nummer staat niet in de bibliotheek",
    "Geen nummers komen door die filters",
    "Aaneenrijgen",
    "Lezen",
    "Geannuleerd",
    "Niet genoeg geheugen",
    "%s opgeslagen (%d nummers, koppeling %d%%)",
    "Kon %s niet schrijven",
    "%lu nummers · %s",
    "De huidige afspeellijst is leeg",
    "%d van %d nummers gevonden",
    "%d nummers om uit te kiezen",
    "Maar %d nummers, ook met open filters",
    "Afspelen stopt om het geheugen te gebruiken",
    "Minuten vanaf",
    "Minuten tot",
    "Andere lijsten",
    "Energie op en neer",
    "Opwarmen",
    "Tot rust komen",
    "Staalkaart van klankfamilies",
    "Het tegenovergestelde van het huidige nummer",
    "Vaste tijd vanaf het huidige nummer",
    "Albums zoals het huidige",
    "Brug van het huidige nummer naar een ander",
    "Minuten",
    "Curves beginnen met huidig nummer",
    "Artiest van het laatste nummer",
    "Laatste nummer",
    "Brug van een ander nummer naar het huidige",
    "Vanaf een willekeurig nummer van een stemming",
    "Automatische crossfade",
    "Duur van de crossfade",
    "Exporteer de bibliotheek opnieuw uit Attacca voor de automatische crossfade",
    "Automatische crossfade aan: werkt op de achtergrond",
    "Automatische crossfade uit: je eigen crossfade is terug",
    "Lange stiltes overslaan",
    "Albums heel, zonder crossfade",
    "Bibliotheek", "Muziek", "Alles",
    "Overvloeien bij overslaan",
};
static const char *const texts_sv[S_COUNT_] = {
    "Attacca",
    "Från aktuell låt",
    "Skapa efter filter",
    "Ordna om aktuell spellista",
    "Stämning, familj, genre, artist",
    "Fler filter",
    "Anpassa filtren till aktuell låt",
    "Samma stämning",
    "Samma ljudfamilj",
    "Låtar",
    "Avstånd mellan artister",
    "När klar",
    "Spela",
    "Bara spara",
    "Börja med aktuell låt",
    "Biblioteksinfo",
    "(alla)",
    "Stämning",
    "Ljudfamilj",
    "Genre",
    "Artist",
    "Rensa dessa filter",
    "År från",
    "År till",
    "Lägsta BPM",
    "Högsta BPM",
    "Energi från",
    "Energi till",
    "Positivitet från",
    "Positivitet till",
    "Röst",
    "Med sång",
    "Instrumental",
    "Bara inkluderade",
    "Ja",
    "Nej",
    "Återställ dessa filter",
    "av",
    "Inget bibliotek: kopiera attaccaplg.bpcr2 till spelarens rot",
    "Inget Attacca Desktop-bibliotek (.bpcr2)",
    "Inget spelas",
    "Aktuell låt finns inte i biblioteket",
    "Inga låtar klarar de filtren",
    "Kedjar",
    "Läser",
    "Avbrutet",
    "Inte tillräckligt med minne",
    "Sparade %s (%d låtar, länk %d%%)",
    "Kunde inte skriva %s",
    "%lu låtar · %s",
    "Aktuell spellista är tom",
    "%d av %d låtar hittades",
    "%d låtar att välja bland",
    "Bara %d låtar, även med öppna filter",
    "Stoppar uppspelningen för att använda minnet",
    "Minuter från",
    "Minuter till",
    "Andra listor",
    "Energi upp och ner",
    "Uppvärmning",
    "Nedvarvning",
    "Smakprov på ljudfamiljer",
    "Motsatsen till aktuell låt",
    "En viss tid från aktuell låt",
    "Album som det aktuella",
    "Bro från aktuell låt till en annan",
    "Minuter",
    "Kurvor börjar med aktuell låt",
    "Artist för sista låten",
    "Sista låten",
    "Bro från en annan låt till aktuell",
    "Från en slumpad låt med en stämning",
    "Automatisk övertoning",
    "Övertoningens längd",
    "Exportera biblioteket igen från Attacca för automatisk övertoning",
    "Automatisk övertoning på: den fortsätter i bakgrunden",
    "Automatisk övertoning av: din egen övertoning är tillbaka",
    "Hoppa över långa tystnader",
    "Hela album, utan övertoning",
    "Bibliotek", "Musik", "Allt",
    "Tona över vid hopp",
};
static const char *const texts_pl[S_COUNT_] = {
    "Attacca",
    "Od bieżącego utworu",
    "Generuj według filtrów",
    "Zmień kolejność bieżącej listy",
    "Nastrój, rodzina, gatunek, wykonawca",
    "Więcej filtrów",
    "Dopasuj filtry do bieżącego utworu",
    "Ten sam nastrój",
    "Ta sama rodzina brzmień",
    "Utwory",
    "Odstęp wykonawcy",
    "Po zakończeniu",
    "Odtwórz",
    "Tylko zapisz",
    "Zacznij od bieżącego utworu",
    "Info o bibliotece",
    "(dowolny)",
    "Nastrój",
    "Rodzina brzmień",
    "Gatunek",
    "Wykonawca",
    "Wyczyść te filtry",
    "Rok od",
    "Rok do",
    "BPM minimum",
    "BPM maksimum",
    "Energia od",
    "Energia do",
    "Pozytywność od",
    "Pozytywność do",
    "Głos",
    "Z wokalem",
    "Instrumentalne",
    "Tylko włączone",
    "Tak",
    "Nie",
    "Zresetuj te filtry",
    "wył.",
    "Brak biblioteki: skopiuj attaccaplg.bpcr2 do katalogu głównego odtwarzacza",
    "To nie biblioteka Attacca Desktop (.bpcr2)",
    "Nic nie jest odtwarzane",
    "Bieżącego utworu nie ma w bibliotece",
    "Żaden utwór nie pasuje do tych filtrów",
    "Łączenie",
    "Odczyt",
    "Anulowano",
    "Za mało pamięci",
    "Zapisano %s (%d utw., łączenie %d%%)",
    "Nie udało się zapisać %s",
    "%lu utw. · %s",
    "Bieżąca lista jest pusta",
    "Znaleziono %d z %d utw.",
    "Do wyboru: %d utw.",
    "Tylko %d utw., nawet z otwartymi filtrami",
    "Zatrzymanie odtwarzania, by użyć jego pamięci",
    "Minuty od",
    "Minuty do",
    "Inne listy",
    "Energia w górę i w dół",
    "Rozgrzewka",
    "Wyciszenie",
    "Przegląd rodzin brzmień",
    "Przeciwieństwo bieżącego utworu",
    "Określony czas od bieżącego utworu",
    "Albumy jak bieżący",
    "Most od bieżącego utworu do innego",
    "Minuty",
    "Krzywe zaczynają od bieżącego utworu",
    "Wykonawca ostatniego utworu",
    "Ostatni utwór",
    "Most od innego utworu do bieżącego",
    "Od losowego utworu o danym nastroju",
    "Automatyczne przenikanie",
    "Długość przenikania",
    "Wyeksportuj bibliotekę ponownie z Attacca dla automatycznego przenikania",
    "Automatyczne przenikanie wł.: działa w tle",
    "Automatyczne przenikanie wył.: wraca twoje przenikanie",
    "Przeskakuj długą ciszę",
    "Całe albumy, bez przenikania",
    "Biblioteka", "Muzyka", "Wszystko",
    "Przenikanie przy przeskoku",
};
static const char *const texts_ro[S_COUNT_] = {
    "Attacca",
    "De la piesa curentă",
    "Generează după filtre",
    "Reordonează lista curentă",
    "Stare, familie, gen, artist",
    "Mai multe filtre",
    "Potrivește filtrele la piesa curentă",
    "Aceeași stare",
    "Aceeași familie de sunet",
    "Piese",
    "Distanța între artiști",
    "La final",
    "Redă",
    "Doar salvează",
    "Începe cu piesa curentă",
    "Info bibliotecă",
    "(oricare)",
    "Stare",
    "Familie de sunet",
    "Gen",
    "Artist",
    "Șterge aceste filtre",
    "An de la",
    "An până la",
    "BPM minim",
    "BPM maxim",
    "Energie de la",
    "Energie până la",
    "Pozitivitate de la",
    "Pozitivitate până la",
    "Voce",
    "Cu voce",
    "Instrumental",
    "Doar incluse",
    "Da",
    "Nu",
    "Resetează aceste filtre",
    "oprit",
    "Fără bibliotecă: copiază attaccaplg.bpcr2 în rădăcina playerului",
    "Nu e o bibliotecă Attacca Desktop (.bpcr2)",
    "Nimic în redare",
    "Piesa curentă nu e în bibliotecă",
    "Nicio piesă nu se potrivește filtrelor",
    "Înlănțuire",
    "Citire",
    "Anulat",
    "Memorie insuficientă",
    "Salvată %s (piese: %d, legătură %d%%)",
    "Nu s-a putut scrie %s",
    "Piese: %lu · %s",
    "Lista curentă e goală",
    "Găsite %d din %d piese",
    "Piese de ales: %d",
    "Doar %d piese, chiar cu filtrele deschise",
    "Redarea se oprește ca să-i folosim memoria",
    "Minute de la",
    "Minute până la",
    "Alte liste",
    "Energie care urcă și coboară",
    "Pentru încălzire",
    "Pentru relaxare",
    "Mostre din familiile de sunet",
    "Opusul piesei curente",
    "Un timp dat de la piesa curentă",
    "Albume ca cel curent",
    "Pod de la piesa curentă la alta",
    "Minute",
    "Curbele încep cu piesa curentă",
    "Artistul ultimei piese",
    "Ultima piesă",
    "Pod de la altă piesă la cea curentă",
    "De la o piesă aleatorie a unei stări",
    "Crossfade automat",
    "Durata crossfade",
    "Exportă din nou biblioteca din Attacca pentru crossfade automat",
    "Crossfade automat pornit: continuă în fundal",
    "Crossfade automat oprit: revine crossfade-ul tău",
    "Sari peste liniștile lungi",
    "Albume întregi, fără crossfade",
    "Bibliotecă", "Muzică", "Tot",
    "Estompare la salt",
};
static const char *const texts_ru[S_COUNT_] = {
    "Attacca",
    "От текущего трека",
    "Создать по фильтрам",
    "Переупорядочить текущий",
    "Настроение, семейство, жанр, исполнитель",
    "Ещё фильтры",
    "Подогнать фильтры под текущий трек",
    "То же настроение",
    "То же звуковое семейство",
    "Треки",
    "Повтор исполнителя через",
    "По готовности",
    "Играть",
    "Только сохранить",
    "Начать с текущего трека",
    "О библиотеке",
    "(любое)",
    "Настроение",
    "Звуковое семейство",
    "Жанр",
    "Исполнитель",
    "Сбросить эти фильтры",
    "Год от",
    "Год до",
    "Минимум BPM",
    "Максимум BPM",
    "Энергия от",
    "Энергия до",
    "Позитивность от",
    "Позитивность до",
    "Голос",
    "С голосом",
    "Инструментал",
    "Только включённые",
    "Да",
    "Нет",
    "Сбросить эти фильтры",
    "выкл.",
    "Нет библиотеки: скопируйте attaccaplg.bpcr2 в корень плеера",
    "Не библиотека Attacca Desktop (.bpcr2)",
    "Ничего не играет",
    "Текущего трека нет в библиотеке",
    "Ни один трек не подходит под фильтры",
    "Сцепка",
    "Чтение",
    "Отменено",
    "Недостаточно памяти",
    "Сохранено %s (треков: %d, связь %d%%)",
    "Не удалось записать %s",
    "Треков: %lu · %s",
    "Текущий плейлист пуст",
    "Найдено треков: %d из %d",
    "Треков на выбор: %d",
    "Всего треков: %d, даже с открытыми фильтрами",
    "Воспроизведение остановлено ради памяти",
    "Минуты от",
    "Минуты до",
    "Другие списки",
    "Энергия вверх и вниз",
    "Разогрев",
    "Затихание",
    "Обзор звуковых семейств",
    "Противоположность текущего трека",
    "На заданное время от текущего трека",
    "Альбомы в духе текущего",
    "Мост от текущего трека к другому",
    "Минуты",
    "Кривые начинаются с текущего трека",
    "Исполнитель последнего трека",
    "Последний трек",
    "Мост от другого трека к текущему",
    "От случайного трека настроения",
    "Автоматический кроссфейд",
    "Длительность кроссфейда",
    "Для автоматического кроссфейда экспортируйте библиотеку из Attacca заново",
    "Автоматический кроссфейд включён: работает в фоне",
    "Автоматический кроссфейд выключен: вернулся ваш кроссфейд",
    "Перескакивать длинную тишину",
    "Альбомы целиком, без кроссфейда",
    "Библиотека", "Музыка", "Всё",
    "Кроссфейд при переходе",
};
static const char *const texts_uk[S_COUNT_] = {
    "Attacca",
    "Від поточного треку",
    "Створити за фільтрами",
    "Перевпорядкувати поточний список",
    "Настрій, родина, жанр, виконавець",
    "Інші фільтри",
    "Підігнати фільтри до поточного треку",
    "Той самий настрій",
    "Та сама звукова родина",
    "Треки",
    "Відстань між виконавцями",
    "Після створення",
    "Відтворити",
    "Лише зберегти",
    "Почати з поточного треку",
    "Про бібліотеку",
    "(будь-який)",
    "Настрій",
    "Звукова родина",
    "Жанр",
    "Виконавець",
    "Скинути ці фільтри",
    "Рік від",
    "Рік до",
    "Мінімальний BPM",
    "Максимальний BPM",
    "Енергія від",
    "Енергія до",
    "Позитивність від",
    "Позитивність до",
    "Голос",
    "З голосом",
    "Інструментальні",
    "Лише включені",
    "Так",
    "Ні",
    "Скинути ці фільтри",
    "вимк.",
    "Немає бібліотеки: скопіюйте attaccaplg.bpcr2 у корінь плеєра",
    "Не бібліотека Attacca Desktop (.bpcr2)",
    "Нічого не грає",
    "Поточного треку немає в бібліотеці",
    "Жоден трек не відповідає фільтрам",
    "Зчеплення",
    "Читання",
    "Скасовано",
    "Замало пам’яті",
    "Збережено %s (треків: %d, зв’язок %d%%)",
    "Не вдалося записати %s",
    "Треків: %lu · %s",
    "Поточний список порожній",
    "Знайдено %d з %d треків",
    "Треків на вибір: %d",
    "Лише %d треків, навіть з відкритими фільтрами",
    "Відтворення зупиняється, щоб узяти його пам’ять",
    "Хвилин від",
    "Хвилин до",
    "Інші списки",
    "Енергія вгору й униз",
    "Розігрів",
    "Заспокоєння",
    "Добірка звукових родин",
    "Протилежне до поточного треку",
    "На заданий час від поточного треку",
    "Альбоми, схожі на поточний",
    "Міст від поточного треку до іншого",
    "Хвилини",
    "Криві починаються з поточного треку",
    "Виконавець останнього треку",
    "Останній трек",
    "Міст від іншого треку до поточного",
    "Від випадкового треку настрою",
    "Автоматичний кросфейд",
    "Тривалість кросфейду",
    "Для автоматичного кросфейду знову експортуйте бібліотеку з Attacca",
    "Автоматичний кросфейд увімкнено: він працює у фоні",
    "Автоматичний кросфейд вимкнено: ваш власний кросфейд повернувся",
    "Перестрибувати довгу тишу",
    "Альбоми цілком, без кросфейду",
    "Бібліотека", "Музика", "Усе",
    "Кросфейд при переході",
};
static const char *const texts_el[S_COUNT_] = {
    "Attacca",
    "Από το τρέχον τραγούδι",
    "Δημιουργία με φίλτρα",
    "Αναδιάταξη τρέχουσας λίστας",
    "Διάθεση, οικογένεια, είδος, καλλιτέχνης",
    "Περισσότερα φίλτρα",
    "Φίλτρα σαν το τρέχον τραγούδι",
    "Ίδια διάθεση",
    "Ίδια ηχητική οικογένεια",
    "Τραγούδια",
    "Απόσταση καλλιτέχνη",
    "Στο τέλος",
    "Αναπαραγωγή",
    "Μόνο αποθήκευση",
    "Έναρξη με το τρέχον τραγούδι",
    "Πληροφορίες βιβλιοθήκης",
    "(οποιοδήποτε)",
    "Διάθεση",
    "Ηχητική οικογένεια",
    "Είδος",
    "Καλλιτέχνης",
    "Καθαρισμός φίλτρων",
    "Έτος από",
    "Έτος έως",
    "Ελάχιστα BPM",
    "Μέγιστα BPM",
    "Ενέργεια από",
    "Ενέργεια έως",
    "Θετικότητα από",
    "Θετικότητα έως",
    "Φωνή",
    "Με φωνή",
    "Ορχηστρικό",
    "Μόνο συμπεριλαμβανόμενα",
    "Ναι",
    "Όχι",
    "Επαναφορά φίλτρων",
    "όχι",
    "Χωρίς βιβλιοθήκη: αντιγράψτε το attaccaplg.bpcr2 στη ρίζα της συσκευής",
    "Δεν είναι βιβλιοθήκη του Attacca Desktop (.bpcr2)",
    "Δεν παίζει τίποτα",
    "Το τρέχον τραγούδι δεν είναι στη βιβλιοθήκη",
    "Κανένα τραγούδι δεν ταιριάζει στα φίλτρα",
    "Αλυσίδωση",
    "Ανάγνωση",
    "Ακυρώθηκε",
    "Δεν αρκεί η μνήμη",
    "Αποθηκεύτηκε %s (%d τραγούδια, σύνδεση %d%%)",
    "Αδύνατη η εγγραφή %s",
    "%lu τραγούδια · %s",
    "Η τρέχουσα λίστα είναι κενή",
    "Βρέθηκαν %d από %d τραγούδια",
    "%d τραγούδια για επιλογή",
    "Μόνο %d τραγούδια, ακόμη και με ανοιχτά φίλτρα",
    "Διακοπή αναπαραγωγής για χρήση της μνήμης της",
    "Λεπτά από",
    "Λεπτά έως",
    "Άλλες λίστες",
    "Ενέργεια που ανεβαίνει και πέφτει",
    "Σταδιακό ανέβασμα",
    "Σταδιακό χαλάρωμα",
    "Δείγματα ηχητικών οικογενειών",
    "Το αντίθετο του τρέχοντος τραγουδιού",
    "Για ορισμένο χρόνο από το τρέχον",
    "Άλμπουμ σαν το τρέχον",
    "Γέφυρα από το τρέχον τραγούδι σε άλλο",
    "Λεπτά",
    "Οι καμπύλες ξεκινούν με το τρέχον",
    "Καλλιτέχνης του τελευταίου τραγουδιού",
    "Τελευταίο τραγούδι",
    "Γέφυρα από άλλο τραγούδι στο τρέχον",
    "Από τυχαίο τραγούδι μιας διάθεσης",
    "Αυτόματο crossfade",
    "Διάρκεια crossfade",
    "Εξαγάγετε ξανά τη βιβλιοθήκη από το Attacca για το αυτόματο crossfade",
    "Αυτόματο crossfade ενεργό: συνεχίζει στο παρασκήνιο",
    "Αυτόματο crossfade ανενεργό: επιστρέφει το δικό σας crossfade",
    "Παράλειψη μεγάλων σιωπών",
    "Ολόκληρα άλμπουμ, χωρίς crossfade",
    "Βιβλιοθήκη", "Μουσική", "Όλα",
    "Crossfade στα άλματα",
};
static const char *const texts_tr[S_COUNT_] = {
    "Attacca",
    "Geçerli şarkıdan",
    "Filtrelerle oluştur",
    "Geçerli listeyi yeniden sırala",
    "Ruh hali, aile, tür, sanatçı",
    "Diğer filtreler",
    "Filtreleri geçerli şarkıya uydur",
    "Aynı ruh hali",
    "Aynı ses ailesi",
    "Şarkılar",
    "Sanatçı aralığı",
    "Bitince",
    "Çal",
    "Yalnızca kaydet",
    "Geçerli şarkıyla başla",
    "Kitaplık bilgisi",
    "(herhangi)",
    "Ruh hali",
    "Ses ailesi",
    "Tür",
    "Sanatçı",
    "Bu filtreleri temizle",
    "Yıl başı",
    "Yıl sonu",
    "En düşük BPM",
    "En yüksek BPM",
    "Enerji en az",
    "Enerji en çok",
    "Pozitiflik en az",
    "Pozitiflik en çok",
    "Vokal",
    "Vokalli",
    "Enstrümantal",
    "Yalnızca dahil olanlar",
    "Evet",
    "Hayır",
    "Bu filtreleri sıfırla",
    "kapalı",
    "Kitaplık yok: attaccaplg.bpcr2 dosyasını oynatıcının köküne kopyalayın",
    "Attacca Desktop kitaplığı değil (.bpcr2)",
    "Hiçbir şey çalmıyor",
    "Geçerli şarkı kitaplıkta yok",
    "Bu filtrelere uyan şarkı yok",
    "Zincirleniyor",
    "Okunuyor",
    "İptal edildi",
    "Yetersiz bellek",
    "Kaydedildi: %s (%d şarkı, bağlantı %d%%)",
    "Yazılamadı: %s",
    "%lu şarkı · %s",
    "Geçerli liste boş",
    "%d / %d şarkı bulundu",
    "Seçilecek şarkı: %d",
    "Filtreler açıkken bile yalnızca %d şarkı",
    "Belleğini kullanmak için çalma durduruluyor",
    "Dakika en az",
    "Dakika en çok",
    "Diğer listeler",
    "Yükselip alçalan enerji",
    "Isınma",
    "Sakinleşme",
    "Ses ailelerinden örnekler",
    "Geçerli şarkının tersi",
    "Geçerli şarkıdan belirli süre",
    "Geçerli albüme benzer albümler",
    "Geçerli şarkıdan başkasına köprü",
    "Dakika",
    "Eğriler geçerli şarkıyla başlar",
    "Son şarkının sanatçısı",
    "Son şarkı",
    "Başka şarkıdan geçerli şarkıya köprü",
    "Bir ruh halinden rastgele şarkıdan",
    "Otomatik çapraz geçiş",
    "Çapraz geçiş süresi",
    "Otomatik çapraz geçiş için kitaplığı Attacca'dan yeniden dışa aktarın",
    "Otomatik çapraz geçiş açık: arka planda sürer",
    "Otomatik çapraz geçiş kapalı: kendi çapraz geçişiniz geri döndü",
    "Uzun sessizlikleri atla",
    "Albümler bütün, geçişsiz",
    "Kitaplık", "Müzik", "Hepsi",
    "Atlarken geçiş",
};
static const char *const texts_ja[S_COUNT_] = {
    "Attacca",
    "再生中の曲から",
    "フィルターで生成",
    "現在のリストを並べ替え",
    "ムード、ファミリー、ジャンル、アーティスト",
    "その他のフィルター",
    "フィルターを再生中の曲に合わせる",
    "同じムード",
    "同じサウンドファミリー",
    "曲数",
    "アーティストの間隔",
    "完了後",
    "再生",
    "保存のみ",
    "再生中の曲から開始",
    "ライブラリ情報",
    "（指定なし）",
    "ムード",
    "サウンドファミリー",
    "ジャンル",
    "アーティスト",
    "このフィルターをクリア",
    "開始年",
    "終了年",
    "BPM 最小",
    "BPM 最大",
    "エネルギー下限",
    "エネルギー上限",
    "ポジティブ度下限",
    "ポジティブ度上限",
    "ボーカル",
    "ボーカルあり",
    "インスト",
    "対象のみ",
    "はい",
    "いいえ",
    "このフィルターをリセット",
    "オフ",
    "ライブラリなし: attaccaplg.bpcr2 をプレーヤーのルートにコピーしてください",
    "Attacca Desktop のライブラリ（.bpcr2）ではありません",
    "再生していません",
    "再生中の曲がライブラリにありません",
    "そのフィルターに合う曲はありません",
    "チェーン中",
    "読み込み中",
    "キャンセルしました",
    "メモリ不足",
    "%s を保存（%d 曲、つなぎ %d%%）",
    "%s に書き込めません",
    "%lu 曲 · %s",
    "現在のリストは空です",
    "%d / %d 曲が見つかりました",
    "選べる曲: %d 曲",
    "フィルター全開でも %d 曲だけ",
    "メモリを使うため再生を停止します",
    "分（下限）",
    "分（上限）",
    "その他のリスト",
    "上がって下がるエネルギー",
    "ウォームアップ",
    "クールダウン",
    "サウンドファミリーのサンプラー",
    "再生中の曲の正反対",
    "再生中の曲から指定時間",
    "現在のアルバムに似たアルバム",
    "再生中の曲から別の曲へブリッジ",
    "分",
    "カーブは再生中の曲から開始",
    "最後の曲のアーティスト",
    "最後の曲",
    "別の曲から再生中の曲へブリッジ",
    "ムードのランダムな曲から",
    "自動クロスフェード",
    "クロスフェードの長さ",
    "自動クロスフェードには Attacca からライブラリを書き出し直してください",
    "自動クロスフェード オン: バックグラウンドで続きます",
    "自動クロスフェード オフ: 自分のクロスフェードに戻ります",
    "長い無音を飛ばす",
    "アルバムはそのまま、クロスフェードなし",
    "ライブラリ", "音楽", "すべて",
    "スキップ時にクロスフェード",
};
static const char *const texts_ko[S_COUNT_] = {
    "Attacca",
    "현재 곡에서",
    "필터로 생성",
    "현재 목록 재정렬",
    "분위기, 계열, 장르, 아티스트",
    "필터 더 보기",
    "필터를 현재 곡에 맞추기",
    "같은 분위기",
    "같은 사운드 계열",
    "곡 수",
    "아티스트 간격",
    "완료 후",
    "재생",
    "저장만",
    "현재 곡으로 시작",
    "라이브러리 정보",
    "(모두)",
    "분위기",
    "사운드 계열",
    "장르",
    "아티스트",
    "이 필터 지우기",
    "시작 연도",
    "종료 연도",
    "BPM 최소",
    "BPM 최대",
    "에너지 최소",
    "에너지 최대",
    "긍정도 최소",
    "긍정도 최대",
    "보컬",
    "보컬 있음",
    "연주곡",
    "포함된 곡만",
    "예",
    "아니요",
    "이 필터 초기화",
    "끔",
    "라이브러리 없음: attaccaplg.bpcr2를 플레이어 루트에 복사하세요",
    "Attacca Desktop 라이브러리(.bpcr2)가 아니에요",
    "재생 중인 곡 없음",
    "현재 곡이 라이브러리에 없어요",
    "그 필터에 맞는 곡이 없어요",
    "연결 중",
    "읽는 중",
    "취소됨",
    "메모리 부족",
    "%s 저장됨 (%d곡, 연결 %d%%)",
    "%s에 쓸 수 없음",
    "%lu곡 · %s",
    "현재 목록이 비어 있어요",
    "%d / %d곡 찾음",
    "고를 수 있는 곡 %d개",
    "필터를 다 열어도 %d곡뿐",
    "메모리를 쓰려고 재생을 멈춰요",
    "최소 분",
    "최대 분",
    "다른 목록",
    "오르내리는 에너지",
    "워밍업",
    "쿨다운",
    "사운드 계열 샘플러",
    "현재 곡의 정반대",
    "현재 곡에서 정해진 시간",
    "현재 앨범과 비슷한 앨범",
    "현재 곡에서 다른 곡으로 다리",
    "분",
    "곡선은 현재 곡으로 시작",
    "마지막 곡의 아티스트",
    "마지막 곡",
    "다른 곡에서 현재 곡으로 다리",
    "분위기의 무작위 곡에서",
    "자동 크로스페이드",
    "크로스페이드 길이",
    "자동 크로스페이드를 쓰려면 Attacca에서 라이브러리를 다시 내보내세요",
    "자동 크로스페이드 켬: 백그라운드에서 계속돼요",
    "자동 크로스페이드 끔: 내 크로스페이드로 돌아가요",
    "긴 무음 건너뛰기",
    "앨범은 그대로, 크로스페이드 없이",
    "라이브러리", "음악", "전체",
    "건너뛸 때 크로스페이드",
};
static const char *const texts_zh_cn[S_COUNT_] = {
    "Attacca",
    "从当前歌曲",
    "按筛选条件生成",
    "重排当前列表",
    "情绪、类别、流派、艺术家",
    "更多筛选",
    "按当前歌曲调整筛选",
    "相同情绪",
    "相同声音类别",
    "歌曲数",
    "艺术家间隔",
    "完成后",
    "播放",
    "仅保存",
    "从当前歌曲开始",
    "媒体库信息",
    "（任意）",
    "情绪",
    "声音类别",
    "流派",
    "艺术家",
    "清除这些筛选",
    "起始年份",
    "结束年份",
    "BPM 最小值",
    "BPM 最大值",
    "能量下限",
    "能量上限",
    "积极度下限",
    "积极度上限",
    "人声",
    "有人声",
    "器乐",
    "仅限包含的曲目",
    "是",
    "否",
    "重置这些筛选",
    "关",
    "无媒体库：请将 attaccaplg.bpcr2 复制到播放器根目录",
    "不是 Attacca Desktop 媒体库（.bpcr2）",
    "未在播放",
    "当前歌曲不在媒体库中",
    "没有歌曲符合这些筛选",
    "正在衔接",
    "正在读取",
    "已取消",
    "内存不足",
    "已保存 %s（%d 首，衔接 %d%%）",
    "无法写入 %s",
    "%lu 首 · %s",
    "当前列表为空",
    "找到 %d / %d 首",
    "%d 首可选",
    "筛选全开也只有 %d 首",
    "停止播放以使用其内存",
    "分钟下限",
    "分钟上限",
    "其他列表",
    "能量起伏",
    "热身",
    "放松",
    "声音类别精选",
    "与当前歌曲相反",
    "从当前歌曲起指定时长",
    "与当前专辑相似的专辑",
    "从当前歌曲桥接到另一首",
    "分钟",
    "曲线从当前歌曲开始",
    "最后一首的艺术家",
    "最后一首",
    "从另一首桥接到当前歌曲",
    "从某种情绪的随机歌曲",
    "自动交叉淡化",
    "交叉淡化时长",
    "请从 Attacca 重新导出媒体库以使用自动交叉淡化",
    "自动交叉淡化已开启：在后台继续",
    "自动交叉淡化已关闭：恢复你自己的交叉淡化",
    "跳过长静音",
    "专辑完整播放，不交叉淡化",
    "音乐库", "音乐", "全部",
    "跳过时交叉淡化",
};
static const char *const texts_zh_tw[S_COUNT_] = {
    "Attacca",
    "從目前歌曲",
    "依篩選條件產生",
    "重排目前清單",
    "情緒、類別、曲風、演出者",
    "更多篩選",
    "依目前歌曲調整篩選",
    "相同情緒",
    "相同聲音類別",
    "歌曲數",
    "演出者間隔",
    "完成後",
    "播放",
    "僅儲存",
    "從目前歌曲開始",
    "媒體庫資訊",
    "（不限）",
    "情緒",
    "聲音類別",
    "曲風",
    "演出者",
    "清除這些篩選",
    "起始年份",
    "結束年份",
    "BPM 最小值",
    "BPM 最大值",
    "能量下限",
    "能量上限",
    "正向程度下限",
    "正向程度上限",
    "人聲",
    "有人聲",
    "演奏曲",
    "僅限包含的曲目",
    "是",
    "否",
    "重設這些篩選",
    "關",
    "無媒體庫：請將 attaccaplg.bpcr2 複製到播放器根目錄",
    "不是 Attacca Desktop 媒體庫（.bpcr2）",
    "未在播放",
    "目前歌曲不在媒體庫中",
    "沒有歌曲符合這些篩選",
    "正在銜接",
    "正在讀取",
    "已取消",
    "記憶體不足",
    "已儲存 %s（%d 首，銜接 %d%%）",
    "無法寫入 %s",
    "%lu 首 · %s",
    "目前清單是空的",
    "找到 %d / %d 首",
    "%d 首可選",
    "篩選全開也只有 %d 首",
    "停止播放以使用其記憶體",
    "分鐘下限",
    "分鐘上限",
    "其他清單",
    "能量起伏",
    "暖身",
    "放鬆",
    "聲音類別精選",
    "與目前歌曲相反",
    "從目前歌曲起指定時長",
    "與目前專輯相似的專輯",
    "從目前歌曲橋接到另一首",
    "分鐘",
    "曲線從目前歌曲開始",
    "最後一首的演出者",
    "最後一首",
    "從另一首橋接到目前歌曲",
    "從某種情緒的隨機歌曲",
    "自動交叉淡化",
    "交叉淡化時長",
    "請從 Attacca 重新匯出媒體庫以使用自動交叉淡化",
    "自動交叉淡化已開啟：在背景繼續",
    "自動交叉淡化已關閉：恢復你自己的交叉淡化",
    "略過長靜音",
    "專輯完整播放，不交叉淡化",
    "音樂庫", "音樂", "全部",
    "跳過時交叉淡化",
};
static const struct { const char *lang; const char *const *texts; } more_texts[] = {
    { "catala", texts_ca },
    { "galego", texts_gl },
    { "basque", texts_eu },
    { "portugues", texts_pt },
    { "portugues-brasileiro", texts_pt_br },
    { "francais", texts_fr },
    { "italiano", texts_it },
    { "deutsch", texts_de },
    { "nederlands", texts_nl },
    { "svenska", texts_sv },
    { "polski", texts_pl },
    { "romaneste", texts_ro },
    { "russian", texts_ru },
    { "ukrainian", texts_uk },
    { "greek", texts_el },
    { "turkce", texts_tr },
    { "japanese", texts_ja },
    { "korean", texts_ko },
    { "chinese-simp", texts_zh_cn },
    { "chinese-trad", texts_zh_tw },
    { NULL, NULL }
};
/* END generated texts */

static const char *const *texts = texts_en;
#define T(id) (texts[id])

/* The texts for the player's language (the name of its .lng file); English when there are none. */
static const char *const *texts_for(const char *lang)
{
    char name[48];
    const char *slash = rb->strrchr(lang, '/');
    char *dot, *c;
    int i;
    /* Only the name, in lower case, whether the setting holds "catala", "Catala.lng" or a path. */
    rb->strlcpy(name, slash ? slash + 1 : lang, sizeof(name));
    dot = rb->strrchr(name, '.');
    if (dot)
        *dot = '\0';
    for (c = name; *c; c++)
        if (*c >= 'A' && *c <= 'Z')
            *c += 'a' - 'A';
    if (!rb->strncmp(name, "espa", 4))
        return texts_es;
    for (i = 0; more_texts[i].lang; i++)
        if (!rb->strcmp(name, more_texts[i].lang))
            return more_texts[i].texts;
    return texts_en;
}

/* ======================= Settings ======================= */

#define CFG_FILE "attaccaplg.cfg"
#define CFG_VERSION 1
#define NAME_LEN 96

/* The library's inclusion sets (its fifth dictionary; none in libraries written before), the one
   chosen by name (empty: none, everything) and its bit in the records (-1: none). */
static uint32_t set_n;
static uint32_t *set_off;
static char incl_set_name[NAME_LEN];
static int set_bit = -1;
#define SETS_OFFSET 168
struct record;
static void apply_set(uint32_t i, struct record *r, bool windowed);

static struct {
    int count, spacing, play, start_current, only_inc, same_mood, same_family;
    int year_from, year_to, bpm_min, bpm_max;   /* 0 = no limit */
    int e_min, e_max, p_min, p_max;             /* percent of the library; 0 / 100 = no limit */
    int voice;                                  /* 0 any, 1 voice, 2 instrumental */
    int min_minutes, max_minutes;               /* song length; 0 = no limit (default 1 to 20, as Attacca) */
    int minutes;                                /* length of a list for a set time */
    int curve_current;                          /* energy curves start with the current song */
    int xf_on, xf_len;                          /* automatic crossfade, and its blends (seconds) */
    int xf_saved, xf_user[6];                   /* the user's own crossfade while it runs */
    int xf_jump;                                /* jump to the next song over long silences */
    int blend_albums;                           /* blends inside an album too (off: albums kept whole) */
    int xf_mode_set;                            /* the crossfade mode the plugin set, + 1 (0: none) */
    int skip_fade;                              /* skips by hand blend (with the user's own crossfade) */
} cfg = { 150, -1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 100, 0, 100, 0, 1, 20, 60, 0, 0, 10, 0, { 0 }, 0, 0, 0, 0 };

/* Category filters by name, so they survive a new library; resolved to indexes on load. */
static char cat_name[4][NAME_LEN];          /* artist, genre, mood, family (as the dictionaries) */
static int cat_idx[4] = { -1, -1, -1, -1 };

static struct configdata config[] = {
    { TYPE_INT, 1, 1000, { .int_p = &cfg.count }, "count", NULL },
    { TYPE_INT, -1, 50, { .int_p = &cfg.spacing }, "spacing", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.play }, "play", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.start_current }, "start current", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.only_inc }, "only included", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.same_mood }, "same mood", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.same_family }, "same family", NULL },
    { TYPE_INT, 0, 3000, { .int_p = &cfg.year_from }, "year from", NULL },
    { TYPE_INT, 0, 3000, { .int_p = &cfg.year_to }, "year to", NULL },
    { TYPE_INT, 0, 400, { .int_p = &cfg.bpm_min }, "bpm min", NULL },
    { TYPE_INT, 0, 400, { .int_p = &cfg.bpm_max }, "bpm max", NULL },
    { TYPE_INT, 0, 100, { .int_p = &cfg.e_min }, "energy min", NULL },
    { TYPE_INT, 0, 100, { .int_p = &cfg.e_max }, "energy max", NULL },
    { TYPE_INT, 0, 100, { .int_p = &cfg.p_min }, "positivity min", NULL },
    { TYPE_INT, 0, 100, { .int_p = &cfg.p_max }, "positivity max", NULL },
    { TYPE_INT, 0, 2, { .int_p = &cfg.voice }, "voice", NULL },
    { TYPE_INT, 0, 600, { .int_p = &cfg.min_minutes }, "min minutes", NULL },
    { TYPE_INT, 0, 600, { .int_p = &cfg.max_minutes }, "max minutes", NULL },
    { TYPE_INT, 1, 600, { .int_p = &cfg.minutes }, "list minutes", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.curve_current }, "curve current", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.xf_on }, "auto crossfade", NULL },
    { TYPE_INT, 1, 15, { .int_p = &cfg.xf_len }, "crossfade length", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.xf_saved }, "own crossfade kept", NULL },
    { TYPE_INT, 0, 15, { .int_p = &cfg.xf_user[0] }, "own fade out delay", NULL },
    { TYPE_INT, 0, 15, { .int_p = &cfg.xf_user[1] }, "own fade out duration", NULL },
    { TYPE_INT, 0, 15, { .int_p = &cfg.xf_user[2] }, "own fade in delay", NULL },
    { TYPE_INT, 0, 15, { .int_p = &cfg.xf_user[3] }, "own fade in duration", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.xf_user[4] }, "own fade out mix", NULL },
    { TYPE_INT, 0, 5, { .int_p = &cfg.xf_user[5] }, "own crossfade mode", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.xf_jump }, "jump silences", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.blend_albums }, "blend albums", NULL },
    { TYPE_INT, 0, 6, { .int_p = &cfg.xf_mode_set }, "crossfade mode set", NULL },
    { TYPE_INT, 0, 1, { .int_p = &cfg.skip_fade }, "fade on skips", NULL },
    { TYPE_STRING, 0, NAME_LEN, { .string = cat_name[0] }, "artist", NULL },
    { TYPE_STRING, 0, NAME_LEN, { .string = cat_name[1] }, "genre", NULL },
    { TYPE_STRING, 0, NAME_LEN, { .string = cat_name[2] }, "mood", NULL },
    { TYPE_STRING, 0, NAME_LEN, { .string = cat_name[3] }, "family", NULL },
    { TYPE_STRING, 0, MAX_PATH, { .string = dbpath }, "library", NULL },
    { TYPE_STRING, 0, NAME_LEN, { .string = incl_set_name }, "inclusion set", NULL },
};
#define N_CONFIG ((int)(sizeof(config) / sizeof(config[0])))

/* ======================= Memory ======================= */
/* Bump allocators. The plugin buffer holds what lives while the plugin runs
   (dictionaries, read windows, path hashes) and, above a mark, the work data of
   an operation. If a pool does not fit there, the work data goes to the audio
   buffer (that stops playback, which is replaced by the new playlist anyway). */

struct arena { uint8_t *base; size_t size, used; };
static struct arena pbuf, abuf;
static bool audio_taken, played;

static void *arena_alloc(struct arena *a, size_t n)
{
    n = (n + 7) & ~(size_t)7;
    if (!a->base || a->used + n > a->size)
        return NULL;
    void *p = a->base + a->used;
    a->used += n;
    return p;
}

static void *palloc(size_t n) { return arena_alloc(&pbuf, n); }
static void *alloc(size_t n) { return arena_alloc(audio_taken ? &abuf : &pbuf, n); }

static bool reserve(size_t n)
{
    struct arena *a = audio_taken ? &abuf : &pbuf;
    if (a->used + n + 64 <= a->size)
        return true;
    if (audio_taken)
        return false;
    rb->splash(HZ, T(S_AUDIO_BUFFER));
    size_t size;
    uint8_t *buf = rb->plugin_get_audio_buffer(&size);
    if (!buf || size < n + 64)
        return false;
    audio_taken = true;
    abuf.base = buf;
    abuf.size = size;
    abuf.used = 0;
    return true;
}

static void release(size_t mark)
{
    pbuf.used = mark;
    if (audio_taken && !played) {
        rb->plugin_release_audio_buffer();
        audio_taken = false;
    }
    abuf.used = 0;
}

/* ======================= File access ======================= */

static bool read_at(uint32_t off, void *buf, size_t len)
{
    return rb->lseek(dbfd, off, SEEK_SET) == (off_t)off
        && rb->read(dbfd, buf, len) == (ssize_t)len;
}

static char *get_str(uint32_t off, char *buf, size_t size)
{
    buf[0] = '\0';
    if (off == NONE32 || off >= H.strsize || size < 2)
        return buf;
    size_t len = MIN(size - 1, H.strsize - off);
    if (rb->lseek(dbfd, H.stroff + off, SEEK_SET) < 0)
        return buf;
    ssize_t got = rb->read(dbfd, buf, len);
    buf[got > 0 ? (size_t)got : 0] = '\0';
    buf[size - 1] = '\0';
    return buf;
}

static char *dict_str(int d, uint32_t i, char *buf, size_t size)
{
    if (i >= dict_n[d]) {
        buf[0] = '\0';
        return buf;
    }
    return get_str(dict_off[d][i], buf, size);
}

#define WINDOW 32768
struct window { uint8_t *buf; uint32_t start, len; };
static struct window rwin, ewin;

static bool seq_read(struct window *w, uint32_t off, void *dst, size_t len)
{
    if (len > WINDOW)
        return read_at(off, dst, len);
    if (off < w->start || off + len > w->start + w->len) {
        if (rb->lseek(dbfd, off, SEEK_SET) != (off_t)off)
            return false;
        ssize_t got = rb->read(dbfd, w->buf, WINDOW);
        if (got < (ssize_t)len)
            return false;
        w->start = off;
        w->len = got;
    }
    rb->memcpy(dst, w->buf + (off - w->start), len);
    return true;
}

static bool get_record(uint32_t i, struct record *r)
{
    if (!seq_read(&rwin, H.recoff + i * H.recsize, r, sizeof(*r)))
        return false;
    apply_set(i, r, true);
    return true;
}

static uint32_t rd32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
static uint16_t rd16(const uint8_t *p) { return p[0] | (p[1] << 8); }

static void close_db(void)
{
    if (dbfd >= 0)
        rb->close(dbfd);
    dbfd = -1;
}

/** The bit of the chosen set (by name) in this library; none when it has no set of that name. */
static void resolve_set(void)
{
    char buf[NAME_LEN];
    set_bit = -1;
    for (uint32_t i = 0; incl_set_name[0] && i < set_n; i++)
        if (!rb->strcmp(get_str(set_off[i], buf, sizeof(buf)), incl_set_name))
            set_bit = i;
}

/** A record's "included" flag as the chosen set says (the library's own otherwise). */
static void apply_set(uint32_t i, struct record *r, bool windowed)
{
    if (set_bit < 0)
        return;
    uint8_t m[4];
    uint32_t at = H.recoff + i * H.recsize + SETS_OFFSET;
    if (windowed ? !seq_read(&rwin, at, m, 4) : !read_at(at, m, 4))
        return;
    r->flags = (r->flags & ~F_INCLUDED) | (((rd32(m) >> set_bit) & 1) ? F_INCLUDED : 0);
}

static const char *open_db(const char *path)
{
    close_db();
    dbfd = rb->open(path, O_RDONLY);
    if (dbfd < 0)
        return T(S_NO_DB);
    uint8_t h[HEADER_SIZE];
    if (rb->read(dbfd, h, HEADER_SIZE) != HEADER_SIZE || rb->memcmp(h, MAGIC, 8)) {
        close_db();
        return T(S_BAD_DB);
    }
    H.n = rd32(h + 8);
    H.recsize = rd32(h + 12);
    H.dictoff = rd32(h + 16);
    H.recoff = rd32(h + 20);
    H.edgeoff = rd32(h + 24);
    H.stroff = rd32(h + 28);
    H.strsize = rd32(h + 32);
    H.spacing = rd16(h + 36);
    H.edgesec = rd16(h + 38);
    H.created = rd32(h + 40);
    H.base = rd32(h + 48);
    H.yearmin = rd16(h + 56);
    H.yearmax = rd16(h + 58);
    H.bpm100min = rd16(h + 60);
    H.bpm100max = rd16(h + 62);
    if (H.recsize < sizeof(struct record) || rd32(h + 44) < N_DICTS || rd16(h + 52) != VEC
        || rd16(h + 54) != sizeof(struct edge)) {
        close_db();
        return T(S_BAD_DB);
    }
    uint32_t off = H.dictoff;
    for (int d = 0; d < N_DICTS; d++) {
        uint8_t c[4];
        if (!read_at(off, c, 4)) {
            close_db();
            return T(S_BAD_DB);
        }
        dict_n[d] = rd32(c);
        dict_off[d] = palloc(dict_n[d] * 4 + 4);
        if (!dict_off[d] || (dict_n[d] && !read_at(off + 4, dict_off[d], dict_n[d] * 4))) {
            close_db();
            return T(S_NO_MEMORY);
        }
        off += 4 + dict_n[d] * 4;
    }
    /* The inclusion sets, when the library has them. */
    set_n = 0;
    set_off = NULL;
    set_bit = -1;
    if (rd32(h + 44) > N_DICTS && H.recsize >= SETS_OFFSET + 4) {
        uint8_t c[4];
        if (read_at(off, c, 4) && rd32(c) > 0 && rd32(c) <= 32) {
            set_off = palloc(rd32(c) * 4);
            if (set_off && read_at(off + 4, set_off, rd32(c) * 4))
                set_n = rd32(c);
        }
    }
    get_str(H.base, base, sizeof(base));
    resolve_set();
    rb->strlcpy(dbpath, path, sizeof(dbpath));
    return NULL;
}

static int dict_find(int d, const char *name)
{
    char buf[NAME_LEN];
    if (!name[0])
        return -1;
    for (uint32_t i = 0; i < dict_n[d]; i++)
        if (!rb->strcmp(dict_str(d, i, buf, sizeof(buf)), name))
            return i;
    return -1;
}

static void resolve_filters(void)
{
    for (int c = 0; c < 4; c++) {
        cat_idx[c] = dict_find(c, cat_name[c]);
        if (cat_idx[c] < 0)
            cat_name[c][0] = '\0';
    }
}

/* ======================= Filters ======================= */

/** The limits that are fitted to a seed; 0 (100 for the upper percents) = no limit. */
struct limits {
    int year_from, year_to, bpm_min, bpm_max, e_min, e_max, p_min, p_max, voice;
};

static void limits_of_cfg(struct limits *l)
{
    l->year_from = cfg.year_from; l->year_to = cfg.year_to;
    l->bpm_min = cfg.bpm_min; l->bpm_max = cfg.bpm_max;
    l->e_min = cfg.e_min; l->e_max = cfg.e_max;
    l->p_min = cfg.p_min; l->p_max = cfg.p_max;
    l->voice = cfg.voice;
}

/** Below this voice percentile a song counts as instrumental (as Attacca). */
#define VOICE_PERCENTILE 3000

static bool within(const struct record *r, const struct limits *l)
{
    if (l->year_from && (!r->year || r->year < l->year_from)) return false;
    if (l->year_to && (!r->year || r->year > l->year_to)) return false;
    if (l->bpm_min && r->bpm100 < l->bpm_min * 100) return false;
    if (l->bpm_max && r->bpm100 > l->bpm_max * 100) return false;
    if (l->e_min > 0 && r->energy < l->e_min * 100) return false;
    if (l->e_max < 100 && r->energy > l->e_max * 100) return false;
    if (l->p_min > 0 && r->positivity < l->p_min * 100) return false;
    if (l->p_max < 100 && r->positivity > l->p_max * 100) return false;
    if (l->voice == 1 && r->voice < VOICE_PERCENTILE) return false;
    if (l->voice == 2 && r->voice >= VOICE_PERCENTILE) return false;
    return true;
}

/** A song's mood, or its second one when it sits between two (as Attacca). */
static bool has_mood(const struct record *r, int mood)
{
    return r->mood == mood || (r->mood2 && r->mood2 - 1 == mood);
}

/** Length, artist, genre, inclusion and mood / family (the seed's when `seed` is set and asked, else the chosen ones). */
static bool matches_base(const struct record *r, const struct record *seed)
{
    if (cfg.only_inc && !(r->flags & F_INCLUDED)) return false;
    /* A song of unknown length (0) passes, as in Attacca. */
    if (r->duration && cfg.min_minutes && r->duration < cfg.min_minutes * 60) return false;
    if (r->duration && cfg.max_minutes && r->duration > cfg.max_minutes * 60) return false;
    if (cat_idx[0] >= 0 && r->artist != cat_idx[0]) return false;
    if (cat_idx[1] >= 0 && r->genre != cat_idx[1]) return false;
    if (seed && cfg.same_mood) {
        if (!has_mood(r, seed->mood)) return false;
    } else if (cat_idx[2] >= 0 && !has_mood(r, cat_idx[2])) return false;
    if (seed && cfg.same_family) {
        if (r->family != seed->family) return false;
    } else if (cat_idx[3] >= 0 && r->family != cat_idx[3]) return false;
    return true;
}

/* ======================= Similarity ======================= */

static uint32_t isqrt64(uint64_t v)
{
    uint64_t r = 0, bit = (uint64_t)1 << 62;
    while (bit > v)
        bit >>= 2;
    while (bit) {
        if (v >= r + bit) {
            v -= r + bit;
            r = (r >> 1) + bit;
        } else
            r >>= 1;
        bit >>= 2;
    }
    return (uint32_t)r;
}

/* exp(-i/32) x 65536, i = 0..257 */
static const uint16_t exp_table[258] = {
    65535, 63520, 61565, 59671, 57835, 56056, 54331, 52660, 51039, 49469, 47947, 46472,
    45042, 43656, 42313, 41011, 39750, 38527, 37341, 36192, 35079, 34000, 32954, 31940,
    30957, 30005, 29081, 28187, 27319, 26479, 25664, 24875, 24109, 23368, 22649, 21952,
    21276, 20622, 19987, 19372, 18776, 18199, 17639, 17096, 16570, 16060, 15566, 15087,
    14623, 14173, 13737, 13314, 12905, 12508, 12123, 11750, 11388, 11038, 10698, 10369,
    10050, 9741, 9441, 9151, 8869, 8596, 8332, 8076, 7827, 7586, 7353, 7127,
    6907, 6695, 6489, 6289, 6096, 5908, 5726, 5550, 5380, 5214, 5054, 4898,
    4747, 4601, 4460, 4323, 4190, 4061, 3936, 3815, 3697, 3584, 3473, 3366,
    3263, 3162, 3065, 2971, 2879, 2791, 2705, 2622, 2541, 2463, 2387, 2314,
    2243, 2174, 2107, 2042, 1979, 1918, 1859, 1802, 1746, 1693, 1641, 1590,
    1541, 1494, 1448, 1403, 1360, 1318, 1278, 1238, 1200, 1163, 1128, 1093,
    1059, 1027, 995, 964, 935, 906, 878, 851, 825, 800, 775, 751,
    728, 706, 684, 663, 642, 623, 604, 585, 567, 550, 533, 516,
    500, 485, 470, 456, 442, 428, 415, 402, 390, 378, 366, 355,
    344, 333, 323, 313, 303, 294, 285, 276, 268, 260, 252, 244,
    236, 229, 222, 215, 209, 202, 196, 190, 184, 178, 173, 168,
    162, 157, 153, 148, 143, 139, 135, 131, 127, 123, 119, 115,
    112, 108, 105, 102, 99, 95, 93, 90, 87, 84, 82, 79,
    77, 74, 72, 70, 68, 66, 64, 62, 60, 58, 56, 54,
    53, 51, 50, 48, 47, 45, 44, 42, 41, 40, 39, 37,
    36, 35, 34, 33, 32, 31, 30, 29, 28, 27, 27, 26,
    25, 24, 23, 23, 22, 21
};

/** exp(-num/den) x 65536, num and den >= 0. */
static uint32_t exp_neg(uint32_t num, uint32_t den)
{
    uint64_t t = ((uint64_t)num << 13) / den;    /* x in 1/256 of a table step (32 steps per unit) */
    uint32_t i = (uint32_t)(t >> 8), frac = (uint32_t)(t & 255);
    if (i >= 256)
        return 0;
    return exp_table[i] - (((exp_table[i] - exp_table[i + 1]) * frac) >> 8);
}

static int iabs(int v) { return v < 0 ? -v : v; }

/** How well key b follows key a, x 65536 (as Attacca's Keys.compatibility: harmonic mixing). */
static uint32_t key_compatibility(int a, int b)
{
    int fa = a < 12 ? (a * 7) % 12 : (((a - 12 + 3) % 12) * 7) % 12;
    int fb = b < 12 ? (b * 7) % 12 : (((b - 12 + 3) % 12) * 7) % 12;
    int up = ((fa - fb) % 12 + 12) % 12, down = ((fb - fa) % 12 + 12) % 12;
    int steps = up < down ? up : down;
    bool same_mode = (a >= 12) == (b >= 12);
    if (a == b) return 65536;
    if (steps == 0) return 58982;                 /* relative major / minor: 0.9 */
    if (steps == 1 && same_mode) return 52429;    /* a fifth: 0.8 */
    if (a % 12 == b % 12) return 39322;           /* parallel: 0.6 */
    if (steps == 1) return 39322;
    if (steps == 2) return 26214;                 /* 0.4 */
    return 9830;                                  /* 0.15 */
}

/** Neutral (0.5) plus `value - 0.5` scaled by `clarity` (x 10000), all x 65536. */
static uint32_t as_clear_as(uint32_t value, int clarity)
{
    if (clarity < 0) clarity = 0;
    if (clarity > 10000) clarity = 10000;
    return (uint32_t)(32768 + ((int64_t)value - 32768) * clarity / 10000);
}

/**
 * Edges with timbre and tempo (as Attacca's EdgeFeatures.similarityWithTimbre, weights learnt from
 * music): tempo, timbre, spectrum shape, activity, key and notes.
 */
static uint32_t similarity_with_timbre(const struct edge *end, const struct edge *start, uint32_t harmony, uint32_t bands, uint32_t activity)
{
    int clarity = end->key_clarity < start->key_clarity ? end->key_clarity : start->key_clarity;
    uint32_t key = as_clear_as(key_compatibility(end->key, start->key), clarity);
    uint64_t squares = 0;
    for (int i = 0; i < 13; i++) {
        int32_t d = (int32_t)end->timbre[i] - start->timbre[i];
        squares += (uint64_t)((int64_t)d * d);
    }
    uint32_t timbre = exp_neg(isqrt64(squares), 111);                              /* 1.11 */
    uint32_t tempo = 32768;
    if (end->tempo != 0 && start->tempo != 0) {
        int r = (int)end->tempo - start->tempo;                                    /* octaves x 1000 */
        int d = iabs(r);
        if (iabs(r - 1000) < d) d = iabs(r - 1000);                                /* half or double */
        if (iabs(r + 1000) < d) d = iabs(r + 1000);
        int pulse = end->pulse < start->pulse ? end->pulse : start->pulse;
        tempo = as_clear_as(exp_neg(d, 40), pulse * 2);                            /* 0.04 octaves */
    }
    return (uint32_t)(((uint64_t)4 * harmony + 5 * key + 16 * bands + 29 * timbre + 12 * activity + 33 * tempo) / 99);
}

/** How alike the end of one song is to the start of another, 0..65536 (Attacca's edge similarity). */
static uint32_t edge_similarity(const struct edge *end, const struct edge *start)
{
    int64_t dot = 0;
    for (int i = 0; i < 12; i++)
        dot += (int32_t)end->chroma[i] * start->chroma[i];
    int32_t corr = (int32_t)(dot >> 12);         /* 16384^2 = 1 << 28: correlation x 65536 */
    uint32_t harmony = (uint32_t)((corr + 65536) / 2);
    uint64_t squares = 0;
    for (int i = 0; i < 16; i++) {
        int32_t d = (int32_t)end->bands[i] - start->bands[i];
        squares += (uint64_t)((int64_t)d * d);
    }
    uint32_t timbre = exp_neg(isqrt64(squares / 16), 600);                       /* 6 dB */
    uint32_t level = exp_neg(iabs((int)end->level - start->level), 800);          /* 8 dB */
    uint32_t activity = exp_neg(iabs((int)end->activity - start->activity), 700); /* ratio 0.7 */
    if (end->key >= 0 && start->key >= 0)
        return similarity_with_timbre(end, start, harmony, timbre, activity);
    return (40 * harmony + 35 * timbre + 15 * level + 10 * activity) / 100;
}

/** Distance between two songs' similarity vectors (x 1000). */
static uint32_t distance(const int16_t *a, const int16_t *b)
{
    uint64_t s = 0;
    for (int i = 0; i < VEC; i++) {
        int32_t d = (int32_t)a[i] - b[i];
        s += (uint64_t)((int64_t)d * d);
    }
    return isqrt64(s);
}

struct song {
    uint32_t rec, key;
    uint16_t artist;
    uint8_t flags, pad;
    struct edge *edges;     /* start, end (NULL without edges) */
};

/** A→B link, 0..65536 (0 when either edge is missing). */
static uint32_t transition(const struct song *a, const struct song *b)
{
    if (!a->edges || !b->edges || !(a->flags & F_TAIL) || !(b->flags & F_HEAD))
        return 0;
    return edge_similarity(&a->edges[1], &b->edges[0]);
}

/* ======================= Progress ======================= */

static long last_progress;

static bool progress(const char *label, int cur, int total)
{
    if (TIME_AFTER(*rb->current_tick, last_progress + HZ / 5)) {
        last_progress = *rb->current_tick;
        rb->splash_progress(cur, total, "%s", label);
        if (rb->action_userabort(TIMEOUT_NOBLOCK))
            return false;
    }
    return true;
}

/* ======================= Pools ======================= */

struct cand { uint32_t rec, key; };

static int cmp_cand_key(const void *a, const void *b)
{
    const struct cand *x = a, *y = b;
    if (x->key != y->key)
        return x->key < y->key ? 1 : -1;     /* closest first */
    return x->rec < y->rec ? -1 : x->rec > y->rec;
}

static int cmp_cand_rec(const void *a, const void *b)
{
    const struct cand *x = a, *y = b;
    return x->rec < y->rec ? -1 : x->rec > y->rec;
}

/**
 * The songs of `c` (sorted by record) with their edges. With `vecs` / `energy`, also their
 * similarity vectors (VEC each) and energies, in the same order as the songs.
 */
static struct song *load_songs(struct cand *c, int n, int16_t **vecs, uint16_t **energy)
{
    rb->qsort(c, n, sizeof(*c), cmp_cand_rec);
    size_t extra = (vecs ? VEC * sizeof(int16_t) : 0) + (energy ? sizeof(uint16_t) : 0);
    if (!reserve(n * (sizeof(struct song) + 2 * sizeof(struct edge) + 8 + extra) + 128)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return NULL;
    }
    struct song *s = alloc(n * sizeof(struct song));
    if (!s)
        return NULL;
    if (vecs && !(*vecs = alloc(n * VEC * sizeof(int16_t))))
        return NULL;
    if (energy && !(*energy = alloc(n * sizeof(uint16_t))))
        return NULL;
    struct record r;
    for (int i = 0; i < n; i++) {
        if (!get_record(c[i].rec, &r))
            return NULL;
        if (vecs)
            rb->memcpy(*vecs + i * VEC, r.vec, sizeof(r.vec));
        if (energy)
            (*energy)[i] = r.energy;
        s[i].rec = c[i].rec;
        s[i].key = c[i].key;
        s[i].artist = r.artist;
        s[i].flags = r.flags;
        s[i].edges = NULL;
        if (r.edge != NONE32) {
            s[i].edges = alloc(2 * sizeof(struct edge));
            if (!s[i].edges || !seq_read(&ewin, H.edgeoff + r.edge * 2 * sizeof(struct edge), s[i].edges, 2 * sizeof(struct edge)))
                return NULL;
        }
        if (!progress(T(S_READING), i, n))
            return NULL;
    }
    return s;
}

/* ======================= Chaining ======================= */

#define WEAK 32768            /* links below 0.5 are weak: they weigh more when improving */
#define IMPROVE_WINDOW 200    /* songs improved at once (Attacca: 600; no room for more here) */
#define IMPROVE_PASSES 10
#define MAX_RUN 3

/** What a link is worth when improving an order: its score, a weak one losing 1.5x its shortfall. */
static int32_t worth(uint32_t sc)
{
    return sc >= WEAK ? (int32_t)sc : (int32_t)sc - (int32_t)((WEAK - sc) * 3 / 2);
}

/** Songs in [from, to) whose artist was already among the `spacing` before them (within the range). */
static int broken_rule(const struct song *s, int from, int to, int spacing)
{
    int count = 0;
    if (spacing <= 0)
        return 0;
    for (int i = from; i < to; i++) {
        if (s[i].artist == NONE16)
            continue;
        for (int j = i - 1; j >= from && j >= i - spacing; j--)
            if (s[j].artist == s[i].artist) {
                count++;
                break;
            }
    }
    return count;
}

/** Whether a song of `artist` placed at `at` (before s[at]) keeps the spacing in s[0, len). */
static bool fits_at(const struct song *s, int len, uint16_t artist, int at, int spacing)
{
    if (artist == NONE16)
        return true;
    for (int k = at - 1; k >= 0 && k >= at - spacing; k--)
        if (s[k].artist == artist)
            return false;
    for (int k = at; k < len && k < at + spacing; k++)
        if (s[k].artist == artist)
            return false;
    return true;
}

/**
 * As Attacca's ChainEngine.repairSpacing: near the end the greedy pass may have only songs of
 * one artist left and put them together; each song that comes too soon after another of its
 * artist moves to where it keeps the spacing and loses least in links (s[0] stays first). A
 * song with no such place stays.
 */
static void repair_spacing(struct song *s, int n, int spacing)
{
    if (spacing <= 0 || n < 3)
        return;
    int moves = 0;
    for (int i = 1; i < n;) {
        bool soon = false;
        if (s[i].artist != NONE16 && moves <= 2 * n)
            for (int j = i - 1; j >= 0 && j >= i - spacing; j--)
                if (s[j].artist == s[i].artist) {
                    soon = true;
                    break;
                }
        if (!soon) {
            i++;
            continue;
        }
        struct song t = s[i];
        for (int k = i; k < n - 1; k++)
            s[k] = s[k + 1];
        int len = n - 1, best_at = -1;
        int64_t best = 0;
        for (int at = 1; at <= len; at++) {
            if (!fits_at(s, len, t.artist, at, spacing))
                continue;
            int64_t gain = worth(transition(&s[at - 1], &t));
            if (at < len)
                gain += (int64_t)worth(transition(&t, &s[at])) - worth(transition(&s[at - 1], &s[at]));
            if (best_at < 0 || gain > best) {
                best = gain;
                best_at = at;
            }
        }
        int at = best_at < 0 ? i : best_at;
        for (int k = len; k > at; k--)
            s[k] = s[k - 1];
        s[at] = t;
        if (best_at >= 0) {
            moves++;
            if (at > i)
                continue;   /* what followed moved up one: look again from here */
        }
        i++;
    }
}

static struct song window_copy[IMPROVE_WINDOW];

/** Moves the run s[i..i+run) to just after position `after` (an index outside the run). */
static void move_run(struct song *s, int i, int run, int after)
{
    struct song moved[MAX_RUN];
    rb->memcpy(moved, &s[i], run * sizeof(struct song));
    if (after > i) {
        /* The songs between the run and `after` move back over it. */
        for (int k = i; k <= after - run; k++)
            s[k] = s[k + run];
        rb->memcpy(&s[after - run + 1], moved, run * sizeof(struct song));
    } else {
        /* The songs after `after` up to the run move forward. */
        for (int k = i + run - 1; k >= after + 1 + run; k--)
            s[k] = s[k - run];
        rb->memcpy(&s[after + 1], moved, run * sizeof(struct song));
    }
}

/**
 * Improves s[from, to) in place as Attacca does: runs of 1 to 3 songs move between two others
 * while the links gain worth and the artist rule is not broken more. The window's first song
 * stays, and its last one too unless the window reaches the chain's end (`end`).
 * Returns false if cancelled.
 */
static bool improve_window(struct song *s, int from, int to, int end, int spacing)
{
    int last = to == end ? to : to - 1;
    int rules = broken_rule(s, from, to, spacing);
    for (int pass = 0; pass < IMPROVE_PASSES; pass++) {
        bool improved = false;
        for (int run = 1; run <= MAX_RUN; run++) {
            for (int i = from + 1; i + run <= last; i++) {
                const struct song *before = &s[i - 1];
                const struct song *s0 = &s[i];
                const struct song *s1 = &s[i + run - 1];
                const struct song *after = i + run < to ? &s[i + run] : NULL;
                int32_t removal = -worth(transition(before, s0));
                if (after)
                    removal += worth(transition(before, after)) - worth(transition(s1, after));
                int32_t best = 0;
                int best_at = -1;
                for (int j = from; j < last; j++) {
                    if (j >= i - 1 && j < i + run)
                        continue;
                    const struct song *c = &s[j];
                    const struct song *d = j + 1 < to ? &s[j + 1] : NULL;
                    int32_t gain = removal + worth(transition(c, s0));
                    if (d)
                        gain += worth(transition(s1, d)) - worth(transition(c, d));
                    if (gain > best) {
                        best = gain;
                        best_at = j;
                    }
                }
                if (best_at < 0)
                    continue;
                rb->memcpy(window_copy, &s[from], (to - from) * sizeof(struct song));
                move_run(s, i, run, best_at);
                int now = broken_rule(s, from, to, spacing);
                if (now <= rules) {
                    rules = now;
                    improved = true;
                } else {
                    rb->memcpy(&s[from], window_copy, (to - from) * sizeof(struct song));
                }
            }
            if (!progress(T(S_CHAINING), pass * MAX_RUN + run, IMPROVE_PASSES * MAX_RUN))
                return false;
        }
        if (!improved)
            break;
    }
    return true;
}

#ifdef HOST_TEST
/* Development (host_test only): mean, worst tenth and worst link and the artist-rule breaks. */
static int cmp_u32(const void *a, const void *b)
{
    uint32_t x = *(const uint32_t *)a, y = *(const uint32_t *)b;
    return x < y ? -1 : x > y;
}

/* Defined in host_test.c: what a finished list is like. */
static void host_list(const struct song *s, int n, const char *name, uint32_t avg);

static void host_stats(const char *label, const struct song *s, int n, int spacing)
{
    if (n < 2)
        return;
    static uint32_t links[4096];
    int m = n - 1 < 4096 ? n - 1 : 4096;
    uint64_t sum = 0;
    for (int i = 0; i < m; i++) {
        links[i] = transition(&s[i], &s[i + 1]);
        sum += links[i];
    }
    qsort(links, m, sizeof(links[0]), cmp_u32);
    int tenth = m / 10 > 0 ? m / 10 : 1;
    uint64_t low = 0;
    for (int i = 0; i < tenth; i++)
        low += links[i];
    printf("%s: %d songs, mean %.3f, worst tenth %.3f, worst %.3f, artist rule broken %d\n", label, n,
           sum / (double)m / 65536, low / (double)tenth / 65536, links[0] / 65536.0, broken_rule(s, 0, n, spacing));
}
#endif

/** Improves the chain s[0, n) window by window (half overlapping). Returns false if cancelled. */
static bool improve_chain(struct song *s, int n, int spacing)
{
    if (n < 4)
        return true;
    for (int from = 0; ; from += IMPROVE_WINDOW / 2) {
        int to = from + IMPROVE_WINDOW < n ? from + IMPROVE_WINDOW : n;
        if (!improve_window(s, from, to, n, spacing))
            return false;
        if (to == n)
            return true;
    }
}

/**
 * Greedy end→start chain (as Attacca): starts with `start` (or a random song)
 * and each time picks the most alike remaining song whose artist is not among
 * the last `spacing`; if none, the best one. Stops at `limit` songs. Then the
 * order is improved (see improve_window). Reorders `s` in place; returns the
 * number of songs, or -1 if cancelled.
 */
static int chain(struct song *s, int n, int start, int limit, int spacing, uint32_t *avg)
{
    uint64_t sum = 0;
    if (n <= 0)
        return 0;
    if (limit <= 0 || limit > n)
        limit = n;
    if (start < 0)
        start = rb->rand() % n;
    struct song tmp = s[0];
    s[0] = s[start];
    s[start] = tmp;
    for (int pos = 1; pos < limit; pos++) {
        const struct song *cur = &s[pos - 1];
        int best_valid = -1, best_any = -1;
        uint32_t sv = 0, sa = 0;
        for (int i = pos; i < n; i++) {
            uint32_t sc = transition(cur, &s[i]);
            if (best_any < 0 || sc > sa) {
                sa = sc;
                best_any = i;
            }
            if (best_valid >= 0 && sc <= sv)
                continue;
            bool repeated = false;
            if (s[i].artist != NONE16)
                for (int j = pos - 1; j >= 0 && j >= pos - spacing; j--)
                    if (s[j].artist == s[i].artist) {
                        repeated = true;
                        break;
                    }
            if (!repeated) {
                sv = sc;
                best_valid = i;
            }
        }
        int pick = best_valid >= 0 ? best_valid : best_any;
        sum += best_valid >= 0 ? sv : sa;
        tmp = s[pos];
        s[pos] = s[pick];
        s[pick] = tmp;
        if (!progress(T(S_CHAINING), pos, limit))
            return -1;
    }
#ifdef HOST_TEST
    host_stats("greedy", s, limit, spacing);
#endif
    repair_spacing(s, limit, spacing);
#ifdef HOST_TEST
    host_stats("repaired", s, limit, spacing);
#endif
    if (!improve_chain(s, limit, spacing))
        return -1;
#ifdef HOST_TEST
    host_stats("improved", s, limit, spacing);
#endif
    sum = 0;
    for (int pos = 1; pos < limit; pos++)
        sum += transition(&s[pos - 1], &s[pos]);
    *avg = limit > 1 ? (uint32_t)(sum / (limit - 1)) : 0;
    return limit;
}

/* ======================= Output ======================= */

#ifdef HAVE_SPECIAL_DIRS
#define HOME HOME_DIR
#else
#define HOME ""
#endif
#define PLAYLIST_DIR HOME "/Playlists"

/** Same file names as Attacca Desktop: [A-Za-z0-9_.-], the rest becomes "_". */
static void file_name(char *out, size_t size, const char *name)
{
    size_t o = 0;
    for (const char *p = name; *p && o + 6 < size; p++) {
        char ch = *p;
        bool ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
            || ch == '_' || ch == '.' || ch == '-';
        out[o++] = ok ? ch : '_';
    }
    if (!o)
        out[o++] = 'p';
    rb->strlcpy(out + o, ".m3u8", size - o);
}

#if defined(HAVE_CROSSFADE) && !defined(HOST_TEST)
static bool has_ends(void);
static void xf_take_over(void);
static bool xf_wanted(void);
#endif

static void output(struct song *s, int n, const char *list_name, uint32_t avg)
{
    char name[MAX_PATH], path[MAX_PATH], title[200], artist[NAME_LEN], rel[MAX_PATH];
    struct record r;
#ifdef HOST_TEST
    host_list(s, n, list_name, avg);
#endif
    file_name(name, sizeof(name), list_name);
    if (!rb->dir_exists(PLAYLIST_DIR))
        rb->mkdir(PLAYLIST_DIR);
    rb->snprintf(path, sizeof(path), "%s/%s", PLAYLIST_DIR, name);
    int fd = rb->open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) {
        rb->splashf(HZ * 2, T(S_WRITE_FAILED), path);
        return;
    }
    rb->fdprintf(fd, "#EXTM3U\n");
    for (int i = 0; i < n; i++) {
        if (!read_at(H.recoff + s[i].rec * H.recsize, &r, sizeof(r)))
            break;
        rb->fdprintf(fd, "#EXTINF:%d,%s - %s\n%s/%s\n", r.duration,
                     dict_str(D_ARTIST, r.artist, artist, sizeof(artist)),
                     get_str(r.title, title, sizeof(title)),
                     base, get_str(r.path, rel, sizeof(rel)));
    }
    rb->close(fd);
    rb->splashf(HZ * 2, T(S_SAVED), name, n, (int)((avg * 100 + 32768) >> 16));

    if (!cfg.play || !rb->warn_on_pl_erase())
        return;
#if defined(HAVE_CROSSFADE) && !defined(HOST_TEST)
    /* Rockbox sizes its sound buffer from the crossfade settings: set the automatic crossfade's
       now, while nothing plays, or doing it on leaving makes Rockbox set the buffer up again
       just as the list starts (it sounded odd until stopped and started again). */
    rb->audio_stop();
    if (audio_taken) {
        rb->plugin_release_audio_buffer();
        audio_taken = false;
    }
    if ((cfg.xf_on && has_ends()) || (!cfg.xf_on && xf_wanted()))
        xf_take_over();
#else
    if (audio_taken) {
        rb->plugin_release_audio_buffer();
        audio_taken = false;
    }
#endif
    if (rb->playlist_create(PLAYLIST_DIR, name) == 0) {
        rb->playlist_start(0, 0, 0);
        played = true;
    }
}

/* ======================= Path matching ======================= */

static uint32_t *hpath, *htail;
static bool hashes_read;

static uint32_t hash_bytes(const char *p, const char *end)
{
    uint32_t h = 0x811c9dc5u;
    for (; p < end && *p; p++) {
        uint8_t b = (uint8_t)*p;
        if (b >= 'A' && b <= 'Z')
            b += 32;
        h = (h ^ b) * 0x01000193u;
    }
    return h;
}

static const char *tail_of(const char *path)
{
    const char *last = rb->strrchr(path, '/');
    if (!last)
        return path;
    const char *p = last;
    while (p > path && p[-1] != '/')
        p--;
    return p == path && *p == '/' ? p + 1 : (p > path ? p : path);
}

/** Room for the path hashes of every song (filled by read_hashes). */
static bool alloc_hashes(void)
{
    if (hpath)
        return true;
    hpath = palloc(H.n * 4);
    htail = palloc(H.n * 4);
    if (!hpath || !htail) {
        hpath = htail = NULL;
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return false;
    }
    hashes_read = false;
    return true;
}

/** Reads the hashes of every song (the whole library: seconds on a big one); false when
    [stop] says to give up halfway. */
static bool read_hashes(volatile bool *stop)
{
    if (hashes_read)
        return true;
    struct record r;
    for (uint32_t i = 0; i < H.n; i++) {
        if ((stop && *stop) || !get_record(i, &r))
            return false;
        hpath[i] = r.hpath;
        htail[i] = r.htail;
    }
    hashes_read = true;
    return true;
}

static bool load_hashes(void)
{
    return alloc_hashes() && read_hashes(NULL);
}

static int find_path(const char *path)
{
    uint32_t hp = hash_bytes(path, path + rb->strlen(path));
    const char *t = tail_of(path);
    uint32_t ht = hash_bytes(t, t + rb->strlen(t));
    int tail_match = -1;
    for (uint32_t i = 0; i < H.n; i++) {
        if (hpath[i] == hp)
            return i;
        if (tail_match < 0 && htail[i] == ht)
            tail_match = i;
    }
    return tail_match;
}

/* A song the plugin was opened on (Open with… on a file): the seed instead of the one playing. */
static char chosen_song[MAX_PATH];

/** The seed song's path: the one the plugin was opened on, else the one playing; NULL without. */
static const char *seed_song_path(void)
{
    if (chosen_song[0])
        return chosen_song;
    struct mp3entry *id3 = rb->audio_current_track();
    return id3 && id3->path[0] ? id3->path : NULL;
}

/** The record of the seed song (chosen or playing), or -1 (with a message). */
static int current_record(void)
{
    const char *path = seed_song_path();
    if (!path) {
        rb->splash(HZ * 2, T(S_NOTHING_PLAYING));
        return -1;
    }
    if (!load_hashes())
        return -1;
    int rec = find_path(path);
    if (rec < 0)
        rb->splash(HZ * 2, T(S_NOT_IN_DB));
    return rec;
}

/* ======================= Automatic crossfade ======================= */
/* As Attacca's automatic crossfade: each join planned from how the song that ends and the next
   one start and end, set in Rockbox's own crossfade settings just before Rockbox reads them (at
   every join). The silences at the end of one song and the start of the next are hidden inside
   the blend, by starting the next song that much earlier. */

/* How a song starts and ends (record + 160 in libraries written for it; see PlayerDb). */
struct ends {
    uint16_t start;     /* where its sound starts, ×10 ms */
    uint16_t tail;      /* silence after its sound, ×10 ms */
    uint16_t fade;      /* length of the fade-out it makes itself, ×10 ms */
    uint8_t ending;     /* END_* */
    uint8_t soft;       /* starts softly */
};
enum { END_UNKNOWN, END_OTHER, END_FADES, END_HARD };
#define ENDS_OFFSET 160
#define ENDS_SIZE 8

/* Rockbox's limits for its crossfade settings (seconds). The PCM buffer holds the fade-out delay
   plus its duration (176 KB a second), taken from the audio buffer: a build with little memory
   (Rockbox for Android comes with 8 MB, under 2 MB left for sound) keeps the blends short. */
#if defined(MEMORYSIZE) && MEMORYSIZE <= 8
#define XF_MAX_DELAY 1
#define XF_MAX_DURATION 4
#else
#define XF_MAX_DELAY 7
#define XF_MAX_DURATION 15
#endif
/* As Attacca's planner: a hard ending gets a short blend, a fade-out at least this. */
#define XF_SHORT_S 2
#define XF_MIN_NATURAL_S 2

/** One join in Rockbox's terms (seconds, and whether the song that ends keeps its level). */
struct xf_values { int out_delay, out_duration, in_delay, in_duration, mix; };

static bool has_ends(void)
{
    return H.recsize >= ENDS_OFFSET + ENDS_SIZE;
}

static bool read_ends(int rec, struct ends *e)
{
    uint8_t b[ENDS_SIZE];
    if (!has_ends() || rec < 0 || (uint32_t)rec >= H.n
        || !read_at(H.recoff + rec * H.recsize + ENDS_OFFSET, b, ENDS_SIZE))
        return false;
    e->start = rd16(b);
    e->tail = rd16(b + 2);
    e->fade = rd16(b + 4);
    e->ending = b[6];
    e->soft = b[7] & 1;
    return e->ending != END_UNKNOWN;
}

/**
 * The join from song `a` into `b` with blends of `len` seconds (as Attacca's CrossfadePlanner):
 * a song that fades out by itself plays out whole over its own fade; a hard ending gets a short
 * blend; otherwise both fade over the length set; a next song that starts softly is not faded in.
 * `gapless`: the next song of the same album, played on without a blend. The silence after `a`'s
 * sound and before `b`'s goes inside the overlap (whole seconds, rounded down: never music cut).
 */
static void plan_join(const struct ends *a, const struct ends *b, bool gapless, int len, struct xf_values *v)
{
    rb->memset(v, 0, sizeof(*v));
    if (gapless)
        return;
    int blend, mix;
    bool fade_in = !b->soft;
    if (a->ending == END_FADES) {
        blend = (a->fade + 50) / 100;
        int low = len < XF_MIN_NATURAL_S ? len : XF_MIN_NATURAL_S;
        blend = blend < low ? low : (blend > len ? len : blend);
        mix = 1;
    } else if (a->ending == END_HARD) {
        blend = len < XF_SHORT_S ? len : XF_SHORT_S;
        mix = 1;
    } else {
        blend = len;
        mix = 0;
    }
    int head = b->start / 100;
    if (head > XF_MAX_DELAY)
        head = XF_MAX_DELAY;
    int tail = a->tail / 100;
    /* The overlap: the next song starts `head + blend + tail` before the end of this file. Rockbox
       fades the ending song over the last `out_duration` seconds, after `out_delay` at full level. */
    v->mix = mix;
    v->out_delay = head;
    v->out_duration = blend + tail;
    if (v->out_duration > XF_MAX_DURATION) {
        /* A long silence at the end: as much of it as fits. */
        v->out_duration = XF_MAX_DURATION;
    }
    v->in_delay = head;
    v->in_duration = fade_in ? blend : 0;
}

/* Jumping over long silences: below this much silence after the sound, the usual join covers it. */
#define XF_JUMP_TAIL_S 3

/**
 * Whether the join from `a` into `b` is better made by jumping to `b` before `a`'s file ends:
 * the silence after `a`'s sound is long, or too long for Rockbox's fade-out with the blend.
 */
static bool wants_jump(const struct ends *a, int len)
{
    int tail = a->tail / 100;
    return tail >= XF_JUMP_TAIL_S || len + tail > XF_MAX_DURATION;
}

/**
 * The join from `a` into `b` made by jumping: how long before the end of `a`'s sound to jump
 * (milliseconds, returned) and the crossfade then. On a skip by hand Rockbox fades the music
 * still to play from where it is, leaves out the fade-in delay and never mixes, so: the next
 * song's leading silence (up to 7 s) is made up by jumping that much earlier; a song that fades
 * out by itself (or ends hard) stays whole by a fade-out delay, as Attacca's mix, instead of
 * being faded twice; otherwise it fades over the blend.
 */
static long plan_jump(const struct ends *a, const struct ends *b, int len, struct xf_values *v)
{
    rb->memset(v, 0, sizeof(*v));
    int blend;
    bool whole = true;
    if (a->ending == END_FADES) {
        blend = (a->fade + 50) / 100;
        int low = len < XF_MIN_NATURAL_S ? len : XF_MIN_NATURAL_S;
        blend = blend < low ? low : (blend > len ? len : blend);
    } else if (a->ending == END_HARD) {
        blend = len < XF_SHORT_S ? len : XF_SHORT_S;
    } else {
        blend = len;
        whole = false;
    }
    int head = b->start / 100;
    if (head > XF_MAX_DELAY)
        head = XF_MAX_DELAY;
    int span = blend + head;   /* from the jump to the end of `a`'s sound */
    if (whole) {
        v->out_delay = span < XF_MAX_DELAY ? span : XF_MAX_DELAY;
        v->out_duration = span - v->out_delay > 1 ? span - v->out_delay : 1;
    } else {
        v->out_delay = head;
        v->out_duration = blend > XF_MAX_DURATION ? XF_MAX_DURATION : blend;
    }
    v->in_delay = 0;
    v->in_duration = b->soft ? 0 : (span > XF_MAX_DURATION ? XF_MAX_DURATION : span);
    v->mix = 0;
    return span * 1000L;
}

#if defined(HAVE_CROSSFADE) && !defined(HOST_TEST)
#define XF_RESIDENT

#define EV_XF_EXIT  MAKE_SYS_EVENT(SYS_EVENT_CLS_PRIVATE, 0xF0)
#define EV_XF_CHECK MAKE_SYS_EVENT(SYS_EVENT_CLS_PRIVATE, 0xF1)

static struct {
    bool active;
    unsigned int id;
    struct event_queue queue;
    /* The thread opens and writes files (the library, the joins log): file access goes deep into
       the file system and storage drivers. A stack that ran out spilt into the queue just below
       it, and Rockbox stopped in queue_wait_w_tmo (seen on a Shanling Q1); now four times the
       default, with a spare stretch between the two. */
    long guard[64];
    long stack[(DEFAULT_STACK_SIZE * 4) / sizeof(long)];
    int cur, next;            /* the pair set up last */
    uint32_t hcur, hnext;     /* and the hashes of their paths (songs out of the library have no record) */
    struct xf_values plan;    /* the join planned for the end of the current song */
    bool ready;               /* the library's paths are read (before that, joins go by folders) */
    bool armed;               /* the plan is set in Rockbox's settings (near the song's end) */
    long jump_at;             /* where in the current song to jump to the next one (ms), or -1 */
    uint32_t jump_song;       /* hash of the path of the song to jump from */
    struct xf_values jump;    /* the crossfade for that jump */
    int seek_done;            /* the song whose leading silence was jumped over */
    volatile bool stopping;   /* asked to end (stops reading the library halfway) */
} xf;

/* Rockbox sizes its sound buffer from fade-out delay + duration, and checks that size each time
   it starts loading more music: a different sum makes it throw the whole buffer away and set it
   up again, even while a song plays (playback stuck until Rockbox was restarted). So the sum
   stays at the longest join, and a shorter one is the same blend started later: the song that
   ends keeps playing at full level that much longer, and the next one waits as long. */
static void xf_set(const struct xf_values *in)
{
    struct user_settings *s = rb->global_settings;
    /* Never past the sound buffer set aside (the user's own crossfade may be longer). */
    struct xf_values c = *in;
    const struct xf_values *v = &c;
    if (c.out_duration > XF_MAX_DURATION)
        c.out_duration = XF_MAX_DURATION;
    if (c.out_delay + c.out_duration > XF_MAX_DELAY + XF_MAX_DURATION)
        c.out_delay = XF_MAX_DELAY + XF_MAX_DURATION - c.out_duration;
    if (c.in_delay > c.out_delay + c.out_duration)
        c.in_delay = c.out_delay + c.out_duration;
    if (c.in_duration > XF_MAX_DURATION)
        c.in_duration = XF_MAX_DURATION;
    int pad = XF_MAX_DELAY + XF_MAX_DURATION - (v->out_delay + v->out_duration);
    if (pad < 0)
        pad = 0;
    s->crossfade_fade_out_delay = v->out_delay + pad;
    s->crossfade_fade_out_duration = v->out_duration;
    s->crossfade_fade_in_delay = v->in_delay + pad;
    s->crossfade_fade_in_duration = v->in_duration;
    /* A join without a blend (an album played on) is made in mix mode: Rockbox then leaves the
       song that ends as it is and starts the next one right where the sound it holds ends. Out of
       mix mode it shortens the ending song's delay to what it holds (often less than the padding
       above) but not the next one's, which then started in the wrong place: a gap or a jump. */
    bool none = v->out_delay == 0 && v->out_duration == 0 && v->in_delay == 0 && v->in_duration == 0;
    s->crossfade_fade_out_mixmode = none ? 1 : v->mix;
}

/** The user's own crossfade, as it was before the automatic one took over. */
static void xf_user(struct xf_values *v)
{
    v->out_delay = cfg.xf_user[0];
    v->out_duration = cfg.xf_user[1];
    v->in_delay = cfg.xf_user[2];
    v->in_duration = cfg.xf_user[3];
    v->mix = cfg.xf_user[4];
}

/** The folder of a song, from its path in the library. */
static void folder_of(const struct record *r, char *buf, size_t len)
{
    get_str(r->path, buf, len);
    char *slash = rb->strrchr(buf, '/');
    if (slash)
        *slash = '\0';
    else
        buf[0] = '\0';
}

/** `b` follows `a` on its album: same album and folder, and later in it (as Attacca keeps gapless). */
static bool follows_on_album(int a_rec, int b_rec)
{
    struct record a, b;
    if (!get_record(a_rec, &a) || !get_record(b_rec, &b) || a.album != b.album || a.album == NONE32)
        return false;
    static char fa[MAX_PATH], fb[MAX_PATH];
    folder_of(&a, fa, sizeof(fa));
    folder_of(&b, fb, sizeof(fb));
    if (rb->strcmp(fa, fb))
        return false;
    get_str(a.path, fa, sizeof(fa));
    get_str(b.path, fb, sizeof(fb));
    return rb->strcmp(fa, fb) < 0;
}

/**
 * `next` comes after `cur` in the same folder, by file name: an album played through, for songs
 * the library does not know.
 */
static bool follows_in_folder(const char *cur, const char *next)
{
    const char *sa = rb->strrchr(cur, '/'), *sb = rb->strrchr(next, '/');
    if (!sa || !sb || sa - cur != sb - next || rb->strncmp(cur, next, sa - cur))
        return false;
    return rb->strcmp(sa + 1, sb + 1) < 0;
}

/**
 * Whether the background helper has anything to do: the automatic crossfade, or keeping albums
 * whole while the user's own crossfade would blend them.
 */
static bool xf_wanted(void)
{
    if (cfg.xf_on)
        return true;
    int mode = cfg.xf_saved ? cfg.xf_user[5] : rb->global_settings->crossfade;
    return !cfg.blend_albums && mode != CROSSFADE_ENABLE_OFF;
}

/* Each join set up, written down so it can be checked on the player (Rockbox opens .txt files):
   which two songs, how they are joined, and why, in attaccaplg-joins.txt beside the library file
   (on Android, Rockbox's own folder cannot be browsed). Started again past 32 KB. */
#define XF_LOG_NAME "attaccaplg-joins.txt"
#define XF_LOG_MAX 32768

static const char *id3_name(const struct mp3entry *id3)
{
    if (id3->title && id3->title[0])
        return id3->title;
    const char *slash = rb->strrchr(id3->path, '/');
    return slash ? slash + 1 : id3->path;
}

/* One line made in a static buffer and written at once (light on the thread's stack). */
static char xf_line[MAX_PATH + 256];

/** Starts a log line with the time; returns its length. */
static int xf_line_start(void)
{
    struct tm *tm = rb->get_time();
    return rb->snprintf(xf_line, sizeof(xf_line), "%02d:%02d:%02d ",
                        tm ? tm->tm_hour : 0, tm ? tm->tm_min : 0, tm ? tm->tm_sec : 0);
}

/** Appends the line made in xf_line ([n] bytes) to the joins log. */
static void xf_line_write(int n)
{
    static char logpath[MAX_PATH];
    rb->strlcpy(logpath, dbpath, sizeof(logpath));
    char *slash = rb->strrchr(logpath, '/');
    if (!slash)
        return;
    rb->strlcpy(slash + 1, XF_LOG_NAME, sizeof(logpath) - (slash + 1 - logpath));
    int fd = rb->open(logpath, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd < 0)
        return;
    if (rb->lseek(fd, 0, SEEK_END) > XF_LOG_MAX) {
        rb->close(fd);
        fd = rb->open(logpath, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (fd < 0)
            return;
    }
    if (n < 0 || n > (int)sizeof(xf_line) - 2)
        n = rb->strlen(xf_line);
    if (n > (int)sizeof(xf_line) - 2)
        n = sizeof(xf_line) - 2;
    xf_line[n++] = '\n';
    rb->write(fd, xf_line, n);
    rb->close(fd);
}

static void xf_log(const struct mp3entry *cur, const char *next, const char *how,
                   const struct xf_values *v, long jump_at)
{
    const char *slash = rb->strrchr(next, '/');
    int n = xf_line_start();
    n += rb->snprintf(xf_line + n, sizeof(xf_line) - n, "%s -> %s: %s; out %d+%d s%s, in %d+%d s",
                      id3_name(cur), slash ? slash + 1 : next, how,
                      v->out_delay, v->out_duration, v->mix ? " mixed" : "", v->in_delay, v->in_duration);
    if (n < 0 || n >= (int)sizeof(xf_line) - 32)
        n = rb->strlen(xf_line);
    if (jump_at >= 0)
        n += rb->snprintf(xf_line + n, sizeof(xf_line) - n, "; jump at %ld:%02ld", jump_at / 60000, (jump_at / 1000) % 60);
    xf_line_write(n);
}

/* The next song's path, from the playlist. Not from audio_next_track(): asked before Rockbox has
   loaded that song, it keeps the file name alone as the song's details until the next change
   (the WPS showed upcoming songs half empty), and right after playback (re)started it left
   playback stuck. */
static struct playlist_track_info xf_next_info;

static const char *xf_next_path(void)
{
    struct playlist_info *pl = rb->playlist_get_current();
    int amount = rb->playlist_amount();
    int i = pl->index + 1;
    if (i >= amount) {
        if (rb->global_settings->repeat_mode != REPEAT_ALL || amount < 1)
            return NULL;
        i = 0;
    }
    if (rb->playlist_get_track_info(pl, i, &xf_next_info) < 0 || !xf_next_info.filename[0])
        return NULL;
    return xf_next_info.filename;
}

/**
 * The values a skip by hand finds: the user's own crossfade when skips blend, else none (a clean
 * cut). Rockbox has one set of values for every change, so these stay in place for most of the
 * song and the join's own go in only near its end ([xf_arm]).
 */
static void xf_manual(void)
{
    struct xf_values v;
    if (cfg.skip_fade)
        xf_user(&v);
    else
        rb->memset(&v, 0, sizeof(v));
    xf_set(&v);
}

/* How long before the end of a song its join is set: Rockbox decides the join when it has decoded
   the song to its end, its sound buffer (the crossfade's delay and length) ahead of the playing. */
#define XF_ARM_MS ((XF_MAX_DELAY + XF_MAX_DURATION + 10) * 1000L)

/** Sets the planned join once the song nears its end; back to the skip values if moved back by hand. */
static bool xf_is_none(const struct xf_values *v)
{
    return v->out_delay == 0 && v->out_duration == 0 && v->in_delay == 0 && v->in_duration == 0;
}

/**
 * Rockbox's crossfade mode the plugin works with: "Always" when skips by hand blend (or jumps
 * over silences, which are skips for Rockbox), else the user's own, "automatic track change only"
 * when theirs was off.
 */
static int xf_base_mode(void)
{
    if ((cfg.xf_on && cfg.xf_jump) || cfg.skip_fade)
        return CROSSFADE_ENABLE_ALWAYS;
    return cfg.xf_user[5] == CROSSFADE_ENABLE_OFF ? CROSSFADE_ENABLE_AUTOSKIP : cfg.xf_user[5];
}

/**
 * Sets Rockbox's crossfade mode. Between modes that crossfade at all the sound buffer keeps its
 * size, so this takes effect at once without stopping the music.
 */
static void xf_mode(int mode)
{
    if (rb->global_settings->crossfade == mode)
        return;
    rb->global_settings->crossfade = mode;
    cfg.xf_mode_set = mode + 1;
    rb->settings_apply(false);
}

static void xf_arm(void)
{
    struct mp3entry *cur = rb->audio_current_track();
    if (!cur || !cur->path[0] || hash_bytes(cur->path, cur->path + rb->strlen(cur->path)) != xf.hcur)
        return;
    long left = (long)cur->length - (long)cur->elapsed;
    if (!xf.armed && left <= XF_ARM_MS) {
        xf_set(&xf.plan);
        /* A join without a blend: Rockbox's own gapless playback, by crossfading only skips by
           hand for this change (a crossfade of no length still blended the two songs on players). */
        xf_mode(xf_is_none(&xf.plan) ? CROSSFADE_ENABLE_MANSKIP : xf_base_mode());
        xf.armed = true;
    } else if (xf.armed && left > XF_ARM_MS + 5000) {
        xf_manual();
        xf_mode(xf_base_mode());
        xf.armed = false;
    }
}

/** Sets up the join between the song playing and the next one, once both are known. */
static void xf_check(void)
{
    struct mp3entry *cur = rb->audio_current_track();
    if (!cur || !cur->path[0])
        return;
    const char *next = xf_next_path();
    if (!next)
        return;
    /* Until the library's paths are read (it takes a while with a big one), songs are told
       apart by their folders only. */
    int a = xf.ready ? find_path(cur->path) : -1, b = xf.ready ? find_path(next) : -1;
    /* The same two songs as last time: nothing to do. By their paths, as songs out of the
       library (no record) would all look alike: a list of such songs then an album kept the
       list's join for the album's first. */
    uint32_t hc = hash_bytes(cur->path, cur->path + rb->strlen(cur->path));
    uint32_t hn = hash_bytes(next, next + rb->strlen(next));
    if (hc == xf.hcur && hn == xf.hnext)
        return;
    xf.cur = a;
    xf.next = b;
    xf.hcur = hc;
    xf.hnext = hn;
    xf.jump_at = -1;
    struct ends ea, eb;
    struct xf_values v;
    const char *how;
    bool keep = !cfg.blend_albums;
    if (a < 0 || b < 0) {
        if (keep && follows_in_folder(cur->path, next)) {
            rb->memset(&v, 0, sizeof(v));
            how = "same folder, next in order: no blend";
        } else {
            xf_user(&v);    /* a song out of the library: the user's own crossfade */
            how = a < 0 ? "this song is not in the library: your own crossfade" : "the next song is not in the library: your own crossfade";
        }
    } else if (!cfg.xf_on || !read_ends(a, &ea) || !read_ends(b, &eb)) {
        if (keep && (follows_on_album(a, b) || follows_in_folder(cur->path, next))) {
            rb->memset(&v, 0, sizeof(v));
            how = "same album: no blend";
        } else {
            xf_user(&v);
            how = cfg.xf_on ? "the library has no song ends (export it again): your own crossfade" : "your own crossfade";
        }
    } else {
        bool gapless = keep && follows_on_album(a, b);
        plan_join(&ea, &eb, gapless, cfg.xf_len, &v);
        how = gapless ? "same album: no gap, no blend"
            : ea.ending == END_FADES ? "it fades out by itself: whole, mixed"
            : ea.ending == END_HARD ? "it ends loud: kept, short blend"
            : eb.soft ? "crossfade; the next one starts softly (no fade-in)"
            : "crossfade";
        if (cfg.xf_jump && !gapless && wants_jump(&ea, cfg.xf_len)) {
            long before = plan_jump(&ea, &eb, cfg.xf_len, &xf.jump);
            long sound_end = (long)cur->length - ea.tail * 10L;
            if (sound_end - before > 0) {
                xf.jump_at = sound_end - before;
                xf.jump_song = hash_bytes(cur->path, cur->path + rb->strlen(cur->path));
            }
        }
    }
    xf.plan = v;
    xf.armed = false;
    xf_manual();
    xf_arm();
    xf_log(cur, next, how, &v, xf.jump_at);
}

/* Seeking by hand (fast forward, rewind) moves the time by leaps; the jump waits until the song
   has played on steadily for a while, and a seek that lands well past the jump leaves the end to
   Rockbox's own crossfade (jumping then would cut what was just chosen). */
#define XF_STEADY_TICKS 15     /* 1.5 s at ten checks a second */
#define XF_LEAP_MS 1000        /* more than this between two checks is a seek */
#define XF_LATE_MS 3000        /* past the jump by more than this: no jump */

/** Jumps to the next song when the moment set for it comes (only while playing that song). */
static void xf_tick(void)
{
    static long last_elapsed = -1;
    static int steady = 0;
    if (xf.jump_at < 0) {
        last_elapsed = -1;
        steady = 0;
        return;
    }
    int status = rb->audio_status();
    if (!(status & AUDIO_STATUS_PLAY) || (status & AUDIO_STATUS_PAUSE))
        return;
    struct mp3entry *cur = rb->audio_current_track();
    if (!cur || hash_bytes(cur->path, cur->path + rb->strlen(cur->path)) != xf.jump_song) {
        xf.jump_at = -1;
        return;
    }
    long elapsed = (long)cur->elapsed;
    if (last_elapsed >= 0) {
        long step = elapsed - last_elapsed;
        if (step < 0 || step > XF_LEAP_MS)
            steady = 0;             /* moved by hand */
        else if (steady < XF_STEADY_TICKS)
            steady++;
    }
    last_elapsed = elapsed;
    if (steady < XF_STEADY_TICKS || elapsed < xf.jump_at)
        return;
    if (elapsed > xf.jump_at + XF_LATE_MS) {
        xf.jump_at = -1;            /* landed past it by hand: Rockbox ends the song itself */
        return;
    }
    xf.jump_at = -1;
    xf_set(&xf.jump);
    rb->audio_next();
}

/** A song that starts with more silence than a join can hide: straight to where its sound starts. */
static void xf_skip_leading_silence(void)
{
    struct mp3entry *cur = rb->audio_current_track();
    if (!cur || !cur->path[0])
        return;
    int rec = find_path(cur->path);
    struct ends e;
    if (rec < 0 || rec == xf.seek_done || !read_ends(rec, &e))
        return;
    xf.seek_done = rec;
    long start = e.start * 10L;
    if (start > XF_MAX_DELAY * 1000L && (long)cur->elapsed < start - 1000) {
        /* Written down first: if the player stops here, the log says so. */
        int n = xf_line_start();
        n += rb->snprintf(xf_line + n, sizeof(xf_line) - n, "%s: silence at the start, on to %ld:%02ld",
                          id3_name(cur), (start - 500) / 60000, ((start - 500) / 1000) % 60);
        xf_line_write(n);
        rb->audio_ff_rewind(start - 500);
    }
}

static void xf_event(unsigned short id, void *data)
{
    (void)id;
    (void)data;
    if (xf.active)
        rb->queue_post(&xf.queue, EV_XF_CHECK, 0);
}

static void xf_thread(void)
{
    struct queue_event ev;
    bool in_usb = false;
    /* The songs' paths are read here, not before leaving the menu (that held the player for
       seconds with a big library); without them the joins are left to Rockbox. */
    /* The first join at once, by folders, while the library's paths are read. */
    xf.ready = false;
    xf_check();
    bool ready = read_hashes(&xf.stopping);
    xf.ready = ready;
    xf.hcur = xf.hnext = 0;   /* the same songs again, now with the library */
    while (true) {
        /* Also every few seconds: the next song may be known only a while after a change; ten
           times a second while a jump is due. */
        rb->queue_wait_w_tmo(&xf.queue, &ev, xf.jump_at >= 0 ? HZ / 10 : HZ * 3);
        switch (ev.id) {
        case EV_XF_EXIT:
            return;
        case SYS_USB_CONNECTED:
            rb->usb_acknowledge(SYS_USB_CONNECTED_ACK, ev.data);
            in_usb = true;
            break;
        case SYS_USB_DISCONNECTED:
            in_usb = false;
            break;
        default:
            if (in_usb || !ready)
                break;
            /* While a jump is due, the quick polls only watch the time. */
            if (ev.id != SYS_TIMEOUT || xf.jump_at < 0) {
                if (cfg.xf_on && cfg.xf_jump)
                    xf_skip_leading_silence();
                xf_check();
            }
            xf_arm();
            xf_tick();
            break;
        }
    }
}

static void xf_stop_thread(void)
{
    if (!xf.active)
        return;
    rb->remove_event(PLAYBACK_EVENT_TRACK_CHANGE, xf_event);
    rb->remove_event(PLAYBACK_EVENT_TRACK_BUFFER, xf_event);
    rb->remove_event(PLAYBACK_EVENT_START_PLAYBACK, xf_event);
    rb->remove_event(PLAYBACK_EVENT_NEXTTRACKID3_AVAILABLE, xf_event);
    xf.stopping = true;
    rb->queue_post(&xf.queue, EV_XF_EXIT, 0);
    rb->thread_wait(xf.id);
    rb->queue_delete(&xf.queue);
    xf.active = false;
}

/* Called before another plugin starts (or this one again): stop listening; this one again
   opens its menu, and goes on in the background when it closes if still turned on. */
static int xf_exit_tsr(bool reenter)
{
    xf_stop_thread();
    /* Back to the longest join (the last one set may be shorter): if the menu takes the sound
       memory to make a list, Rockbox sets its buffer up again from these. Not when another
       plugin starts: the join set up for the songs playing must stay meanwhile. */
    if (reenter && cfg.xf_saved) {
        rb->global_settings->crossfade_fade_out_delay = XF_MAX_DELAY;
        rb->global_settings->crossfade_fade_out_duration = XF_MAX_DURATION;
    }
    close_db();
    return reenter ? PLUGIN_TSR_TERMINATE : PLUGIN_TSR_SUSPEND;
}

/* Applying a new crossfade makes Rockbox set its sound buffer up again; doing it while a song
   plays left playback stuck on the next song with no sound until Rockbox was restarted. So the
   song stops first and goes on from the same place afterwards. */
static void xf_apply(void)
{
    int status = rb->audio_status();
    bool playing = status & AUDIO_STATUS_PLAY;
    struct mp3entry *id3 = playing ? rb->audio_current_track() : NULL;
    unsigned long elapsed = id3 ? id3->elapsed : 0, offset = id3 ? id3->offset : 0;
    int index = rb->playlist_get_current()->index;
    if (playing)
        rb->audio_stop();
    rb->settings_apply(false);
    if (playing && index >= 0) {
        rb->playlist_start(index, elapsed, offset);
        if (status & AUDIO_STATUS_PAUSE)
            rb->audio_pause();
    }
}

/** Keeps the user's crossfade and makes room for the longest joins (Rockbox sizes its buffer then). */
static void xf_take_over(void)
{
    struct user_settings *s = rb->global_settings;
    if (!cfg.xf_saved) {
        cfg.xf_user[0] = s->crossfade_fade_out_delay;
        cfg.xf_user[1] = s->crossfade_fade_out_duration;
        cfg.xf_user[2] = s->crossfade_fade_in_delay;
        cfg.xf_user[3] = s->crossfade_fade_in_duration;
        cfg.xf_user[4] = s->crossfade_fade_out_mixmode;
        cfg.xf_user[5] = s->crossfade;
        cfg.xf_saved = 1;
    } else if (cfg.xf_mode_set && s->crossfade != cfg.xf_mode_set - 1) {
        /* Changed by the user in Rockbox's settings since: that is their crossfade now. */
        cfg.xf_user[5] = s->crossfade;
    }
    bool apply = false;
    /* Jumping is a skip: Rockbox must crossfade those too. */
    int mode = xf_base_mode();
    if (s->crossfade != mode) {
        s->crossfade = mode;
        apply = true;
    }
    cfg.xf_mode_set = mode + 1;
    /* Only the sum sizes the buffer (each join keeps it: see xf_set). No blend meanwhile (the
       values xf_set gives keep that sum): until the first join is worked out, a song never
       blends with the full length set aside for the longest joins. */
    if (s->crossfade_fade_out_delay + s->crossfade_fade_out_duration != XF_MAX_DELAY + XF_MAX_DURATION)
        apply = true;
    struct xf_values none;
    rb->memset(&none, 0, sizeof(none));
    xf_set(&none);
    if (apply)
        xf_apply();
}

/** Puts the user's own crossfade back. */
static void xf_give_back(void)
{
    if (!cfg.xf_saved)
        return;
    struct xf_values v;
    xf_user(&v);
    xf_set(&v);
    /* A mode the user chose meanwhile in Rockbox's settings stays; else theirs from before. */
    int now = rb->global_settings->crossfade;
    if (!cfg.xf_mode_set || now == cfg.xf_mode_set - 1)
        rb->global_settings->crossfade = cfg.xf_user[5];
    cfg.xf_mode_set = 0;
    cfg.xf_saved = 0;
    xf_apply();
}

/** Stays in the background setting up each join. False (with a message) when it cannot. */
static bool xf_go_resident(void)
{
    if (cfg.xf_on && !has_ends()) {
        rb->splash(HZ * 3, T(S_XF_OLD_DB));
        return false;
    }
    if (!alloc_hashes())
        return false;
    xf_take_over();
    xf.cur = xf.next = -1;
    xf.hcur = xf.hnext = 0;
    xf.jump_at = -1;
    xf.seek_done = -1;
    xf.stopping = false;
    rb->queue_init(&xf.queue, true);
    xf.id = rb->create_thread(xf_thread, xf.stack, sizeof(xf.stack), 0, "Attacca crossfade"
                              IF_PRIO(, PRIORITY_BACKGROUND) IF_COP(, CPU));
    if (!xf.id) {
        rb->queue_delete(&xf.queue);
        return false;
    }
    xf.active = true;
    rb->add_event(PLAYBACK_EVENT_TRACK_CHANGE, xf_event);
    rb->add_event(PLAYBACK_EVENT_TRACK_BUFFER, xf_event);
    /* A new list started (an album after a list): its first join set up at once. */
    rb->add_event(PLAYBACK_EVENT_START_PLAYBACK, xf_event);
    rb->add_event(PLAYBACK_EVENT_NEXTTRACKID3_AVAILABLE, xf_event);
    rb->plugin_tsr(xf_exit_tsr);
    rb->queue_post(&xf.queue, EV_XF_CHECK, 0);
    return true;
}
#endif /* HAVE_CROSSFADE */

/* ======================= Modes ======================= */

static int spacing(void)
{
    return cfg.spacing >= 0 ? cfg.spacing : H.spacing;
}

/**
 * Candidates passing the filters (except `skip`) into `c`; returns how many. With `near`, keyed
 * by closeness to it (closest the highest key; farthest the highest with `far`). `same` is the
 * song whose mood and family the switches ask for (see matches_base).
 */
static int scan_by(struct cand *c, int skip, const struct record *near, const struct record *same, bool far)
{
    struct record r;
    struct limits l;
    limits_of_cfg(&l);
    int n = 0;
    for (uint32_t i = 0; i < H.n; i++) {
        if (!get_record(i, &r))
            break;
        if ((int)i != skip && matches_base(&r, same) && within(&r, &l)) {
            uint32_t d = near ? distance(near->vec, r.vec) : 0;
            c[n].rec = i;
            c[n].key = !near ? 0 : far ? d : NONE32 - d;
            n++;
        }
        if ((i & 255) == 0 && !progress(T(S_READING), i, H.n))
            return -1;
    }
    return n;
}

/** Candidates passing the filters (except `skip`) into `c`, keyed by closeness to `seed`; returns how many. */
static int scan(struct cand *c, int skip, const struct record *seed)
{
    return scan_by(c, skip, seed, seed, false);
}

/**
 * As Attacca's ListGenerator.spread: the `take` closest (`c` sorted closest first) that a list of
 * `count` can chain keeping the artist spacing, no artist more often than it allows (the closest
 * songs are often one album; the artists `in1` and `in2`, already in the list, count once
 * already; NONE16 for none); filled with the closest left out when nothing else is there. They
 * end at the start of `c`, closest first.
 */
static bool spread(struct cand *c, int n, int take, int count, uint16_t in1, uint16_t in2)
{
    int sp = spacing();
    if (sp <= 0 || take >= n)
        return true;
    /* Freed with the caller's memory (the arenas are not a stack once the audio buffer is taken). */
    int per = (count + sp) / (sp + 1);
    if (per < 1)
        per = 1;
    uint32_t artists = dict_n[D_ARTIST];
    uint16_t *uses = alloc((artists + 1) * sizeof(*uses));
    struct cand *pick = alloc(take * sizeof(*pick) + 8);
    if (!uses || !pick)
        return false;
    rb->memset(uses, 0, (artists + 1) * sizeof(*uses));
    if (in1 < artists)
        uses[in1]++;
    if (in2 < artists)
        uses[in2]++;
    const uint32_t chosen = 0x80000000u;
    struct record r;
    int k = 0, i = 0;
    for (; i < n && k < take; i++) {
        if (!get_record(c[i].rec, &r))
            break;
        if (r.artist < artists) {
            if (uses[r.artist] >= per)
                continue;
            uses[r.artist]++;
        }
        pick[k++] = c[i];
        c[i].rec |= chosen;
    }
    for (int j = 0; j < i && k < take; j++)
        if (!(c[j].rec & chosen))
            pick[k++] = c[j];
    for (int j = 0; j < i; j++)
        c[j].rec &= ~chosen;
    /* Closest first again (the picks keep their order; those filled in come after). */
    rb->qsort(pick, k, sizeof(*pick), cmp_cand_key);
    rb->memcpy(c, pick, k * sizeof(*pick));
    return true;
}

static int margin_for(int count) { return count < 1 ? 1 : (count + 9) / 10; }

/**
 * As Attacca's ListGenerator.fromSeed: the `count` songs closest to the seed (filters, same mood /
 * family switches) plus a 10 % margin, spread by artist, chained from the seed and cut to `count`.
 * The songs go to `*out`; returns how many, 0 when none match, -1 when cancelled or out of memory
 * (said).
 */
static int seed_list(int seed_rec, const struct record *seed, int count, struct song **out, uint32_t *avg)
{
    struct cand *c = alloc((H.n + 1) * sizeof(*c));
    if (!c) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return -1;
    }
    int n = scan(c, seed_rec, seed);
    if (n < 0) {
        rb->splash(HZ, T(S_CANCELLED));
        return -1;
    }
    if (n == 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        return 0;
    }
    /* The `count` closest plus a 10 % margin: the chain stops at `count`, so the songs the
       artist spacing could not place do not pile up at the end. */
    int take = MIN(n, count + margin_for(count) - 1);
    rb->qsort(c, n, sizeof(*c), cmp_cand_key);
    if (!spread(c, n, take, count, seed->artist, NONE16)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return -1;
    }
    c[take].rec = seed_rec;
    c[take].key = NONE32;
    struct song *s = load_songs(c, take + 1, NULL, NULL);
    if (!s)
        return -1;
    int start = 0;
    for (int i = 0; i <= take; i++)
        if (s[i].rec == (uint32_t)seed_rec)
            start = i;
    int got = chain(s, take + 1, start, count, spacing(), avg);
    if (got < 0) {
        rb->splash(HZ, T(S_CANCELLED));
        return -1;
    }
    *out = s;
    return got;
}

static bool read_record(int rec, struct record *r)
{
    if (rec < 0 || (uint32_t)rec >= H.n || !read_at(H.recoff + rec * H.recsize, r, sizeof(*r)))
        return false;
    apply_set(rec, r, false);
    return true;
}

static void from_current(void)
{
    int seed_rec = current_record();
    if (seed_rec < 0)
        return;
    size_t mark = pbuf.used;
    struct record seed;
    struct song *s;
    uint32_t avg = 0;
    if (!read_record(seed_rec, &seed)) {
        rb->splash(HZ * 2, T(S_BAD_DB));
        return;
    }
    int got = seed_list(seed_rec, &seed, cfg.count, &s, &avg);
    if (got > 0) {
        char title[120], name[140];
        rb->snprintf(name, sizeof(name), "seed_%s", get_str(seed.title, title, sizeof(title)));
        output(s, got, name, avg);
    }
    release(mark);
}

/* The mood chooser of the category filters (below). */
static int pick_dict;
static const char *pick_name(int i, void *data, char *buf, size_t len);

/** A list from a random song of the mood chosen (or of any), as Attacca's random list. */
static void random_list(void)
{
    struct simplelist_info info;
    pick_dict = D_MOOD;
    rb->simplelist_info_init(&info, (char *)T(S_RANDOM), dict_n[D_MOOD] + 1, NULL);
    info.get_name = pick_name;
    info.selection = 0;
    rb->simplelist_show_list(&info);
    if (info.selection < 0)
        return;
    int mood = info.selection - 1;
    /* One song at random among those of the mood (its second one counts too), by reservoir. */
    struct record r;
    int seed_rec = -1, seen = 0;
    for (uint32_t i = 0; i < H.n; i++) {
        if (!get_record(i, &r))
            break;
        if (mood >= 0 && !has_mood(&r, mood))
            continue;
        if (cfg.only_inc && !(r.flags & F_INCLUDED))
            continue;
        if (rb->rand() % ++seen == 0)
            seed_rec = i;
    }
    if (seed_rec < 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        return;
    }
    size_t mark = pbuf.used;
    struct record seed;
    struct song *s;
    uint32_t avg = 0;
    if (!read_record(seed_rec, &seed)) {
        rb->splash(HZ * 2, T(S_BAD_DB));
        return;
    }
    int got = seed_list(seed_rec, &seed, cfg.count, &s, &avg);
    if (got > 0) {
        char title[120], name[140];
        rb->snprintf(name, sizeof(name), "random_%s", get_str(seed.title, title, sizeof(title)));
        output(s, got, name, avg);
    }
    release(mark);
}

/* Fitting the filters to the seed (as Attacca's ListGenerator.fitToSeed): windows around the
   song's year, BPM, energy, positivity and voice, widened step by step; the first step leaving
   10 times the songs asked for is taken, else the first leaving twice, else the widest. Windows
   only widen, so one pass over the library counts every step. */

static const int fit_steps[] = { 10, 15, 20, 30, 40, 60, 80, 120, 200 };   /* x 1/10 */
#define N_STEPS 9
#define N_TRIES (2 * N_STEPS + 3)

static int floor_div(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
static int ceil_div(int a, int b) { return -floor_div(-a, b); }

static void around(const struct record *seed, int step, int voice, bool years, struct limits *l)
{
    int m = (2 * step + 5) / 10;
    l->year_from = l->year_to = 0;
    if (years && seed->year) {
        if (seed->year - m > H.yearmin) l->year_from = seed->year - m;
        if (seed->year + m < H.yearmax) l->year_to = seed->year + m;
    }
    l->bpm_min = l->bpm_max = 0;
    if (seed->bpm100) {
        int lo = (int)((uint32_t)seed->bpm100 * (1000 - 4 * step) / 100000);
        int hi = (int)(((uint32_t)seed->bpm100 * (1000 + 4 * step) + 99999) / 100000);
        if (lo * 100 > H.bpm100min) l->bpm_min = lo;
        if (hi * 100 < H.bpm100max) l->bpm_max = hi;
    }
    /* Percentiles x 10000; margin 5 % per step (50 x step in tenths); limits in steps of 5 %. */
    int e_lo = floor_div(seed->energy - 50 * step, 500) * 5, e_hi = ceil_div(seed->energy + 50 * step, 500) * 5;
    int p_lo = floor_div(seed->positivity - 50 * step, 500) * 5, p_hi = ceil_div(seed->positivity + 50 * step, 500) * 5;
    l->e_min = e_lo > 0 ? e_lo : 0;
    l->e_max = e_hi < 100 ? e_hi : 100;
    l->p_min = p_lo > 0 ? p_lo : 0;
    l->p_max = p_hi < 100 ? p_hi : 100;
    l->voice = voice;
}

static void fit_to_current(void)
{
    int seed_rec = current_record();
    if (seed_rec < 0)
        return;
    struct record seed, r;
    if (!read_at(H.recoff + seed_rec * H.recsize, &seed, sizeof(seed)))
        return;
    int voice = seed.voice >= VOICE_PERCENTILE ? 1 : 2;
    /* Tries: with years (0..8), without years (9..17), any voice (18, 19), no limits (20). */
    struct limits tries[N_TRIES];
    int counts[N_TRIES];
    for (int k = 0; k < N_STEPS; k++) {
        around(&seed, fit_steps[k], voice, true, &tries[k]);
        around(&seed, fit_steps[k], voice, false, &tries[N_STEPS + k]);
    }
    around(&seed, fit_steps[N_STEPS - 2], 0, false, &tries[2 * N_STEPS]);
    around(&seed, fit_steps[N_STEPS - 1], 0, false, &tries[2 * N_STEPS + 1]);
    struct limits open = { 0, 0, 0, 0, 0, 100, 0, 100, 0 };
    tries[2 * N_STEPS + 2] = open;
    rb->memset(counts, 0, sizeof(counts));
    uint32_t with_year = 0;
    for (uint32_t i = 0; i < H.n; i++) {
        if (!get_record(i, &r))
            break;
        if (r.year)
            with_year++;
        if ((int)i != seed_rec && matches_base(&r, &seed))
            for (int t = 0; t < N_TRIES; t++)
                if (within(&r, &tries[t]))
                    counts[t]++;
        if ((i & 255) == 0 && !progress(T(S_READING), i, H.n)) {
            rb->splash(HZ, T(S_CANCELLED));
            return;
        }
    }
    /* A year window leaves out the songs without a year: used only when most songs have one. */
    bool years = seed.year && with_year * 2 >= H.n;
    int order[N_TRIES], n = 0;
    for (int t = years ? 0 : N_STEPS; t < N_TRIES; t++)
        order[n++] = t;
    int chosen = order[n - 1];
    for (int pass = 0; pass < 2 && chosen == order[n - 1]; pass++) {
        int need = cfg.count * (pass == 0 ? 10 : 2);
        for (int j = 0; j < n; j++)
            if (counts[order[j]] >= need) {
                chosen = order[j];
                break;
            }
    }
    const struct limits *l = &tries[chosen];
    cfg.year_from = l->year_from; cfg.year_to = l->year_to;
    cfg.bpm_min = l->bpm_min; cfg.bpm_max = l->bpm_max;
    cfg.e_min = l->e_min; cfg.e_max = l->e_max;
    cfg.p_min = l->p_min; cfg.p_max = l->p_max;
    cfg.voice = l->voice;
    rb->splashf(HZ * 2, counts[chosen] >= cfg.count * 2 ? T(S_FIT_FMT) : T(S_FIT_SHORT), counts[chosen]);
}

static void by_filters(void)
{
    size_t mark = pbuf.used;
    struct cand *c = alloc(H.n * sizeof(*c));
    if (!c) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return;
    }
    int n = scan(c, -1, NULL);
    if (n < 0) {
        rb->splash(HZ, T(S_CANCELLED));
    } else if (n == 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
    } else {
        struct song *s = load_songs(c, n, NULL, NULL);
        uint32_t avg = 0;
        int got = s ? chain(s, n, -1, cfg.count, spacing(), &avg) : 0;
        if (got < 0)
            rb->splash(HZ, T(S_CANCELLED));
        else if (got > 0) {
            /* As Attacca Desktop: mood, family, genre, artist, or "playlist". */
            char name[4 * NAME_LEN] = "";
            static const int order[4] = { 2, 3, 1, 0 };
            for (int k = 0; k < 4; k++)
                if (cat_name[order[k]][0]) {
                    if (name[0]) rb->strlcat(name, "_", sizeof(name));
                    rb->strlcat(name, cat_name[order[k]], sizeof(name));
                }
            output(s, got, name[0] ? name : "playlist", avg);
        }
    }
    release(mark);
}

static void reorder(void)
{
    int total = rb->playlist_amount();
    if (total <= 0) {
        rb->splash(HZ * 2, T(S_EMPTY_PLAYLIST));
        return;
    }
    if (!load_hashes())
        return;
    size_t mark = pbuf.used;
    struct cand *c = alloc(total * sizeof(*c));
    uint8_t *seen = alloc(H.n);
    if (!c || !seen) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        release(mark);
        return;
    }
    rb->memset(seen, 0, H.n);
    struct playlist_track_info info;
    int found = 0, n = 0, current = -1;
    struct mp3entry *id3 = rb->audio_current_track();
    if (id3 && id3->path[0] && cfg.start_current)
        current = find_path(id3->path);
    for (int i = 0; i < total; i++) {
        if (!progress(T(S_READING), i, total)) {
            rb->splash(HZ, T(S_CANCELLED));
            release(mark);
            return;
        }
        if (rb->playlist_get_track_info(NULL, i, &info) < 0)
            continue;
        int rec = find_path(info.filename);
        if (rec < 0 || seen[rec])
            continue;
        seen[rec] = 1;
        found++;
        c[n].rec = rec;
        c[n].key = 0;
        n++;
    }
    rb->splashf(HZ * 2, T(S_REORDER_FMT), found, total);
    if (n == 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        release(mark);
        return;
    }
    uint32_t first = c[0].rec;
    struct song *s = load_songs(c, n, NULL, NULL);
    if (s) {
        int start = -1, first_i = 0;
        for (int i = 0; i < n; i++) {
            if (s[i].rec == (uint32_t)current)
                start = i;
            if (s[i].rec == first)
                first_i = i;
        }
        if (start < 0)
            start = first_i;
        uint32_t avg = 0;
        int got = chain(s, n, start, 0, spacing(), &avg);
        if (got < 0)
            rb->splash(HZ, T(S_CANCELLED));
        else
            output(s, got, "reordered", avg);
    }
    release(mark);
}

/* ======================= More kinds of list ======================= */
/* As Attacca's ListGenerator, region "More kinds of list", and ChainLists.albumsLike. */

/** The record of the song that is playing, or -1 (quietly). */
static int playing_record(void)
{
    const char *path = seed_song_path();
    if (!path || !load_hashes())
        return -1;
    return find_path(path);
}

/** The average link of s[0, n). */
static uint32_t mean_link(const struct song *s, int n)
{
    uint64_t sum = 0;
    for (int i = 1; i < n; i++)
        sum += transition(&s[i - 1], &s[i]);
    return n > 1 ? (uint32_t)(sum / (n - 1)) : 0;
}

/** Positions where each artist was last placed, for the artist rule along a list being built. */
static int32_t *artist_last;
#define NEVER (-(1 << 30))

static bool artist_init(void)
{
    artist_last = alloc((dict_n[D_ARTIST] + 1) * sizeof(*artist_last));
    if (!artist_last)
        return false;
    for (uint32_t a = 0; a <= dict_n[D_ARTIST]; a++)
        artist_last[a] = NEVER;
    return true;
}

/** Whether `artist` is among the `sp` songs before position `pos`. */
static bool artist_recent(uint16_t artist, int pos, int sp)
{
    return artist < dict_n[D_ARTIST] && artist_last[artist] >= pos - sp;
}

static void artist_placed(uint16_t artist, int pos)
{
    if (artist < dict_n[D_ARTIST])
        artist_last[artist] = pos;
}

static int cmp_u32_up(const void *a, const void *b)
{
    uint32_t x = *(const uint32_t *)a, y = *(const uint32_t *)b;
    return x < y ? -1 : x > y;
}

static int cmp_song_key_up(const void *a, const void *b)
{
    const struct song *x = a, *y = b;
    if (x->key != y->key)
        return x->key < y->key ? -1 : 1;
    return x->rec < y->rec ? -1 : x->rec > y->rec;
}

/* Energy curves. */
enum { CURVE_RISE_FALL, CURVE_WARM_UP, CURVE_WIND_DOWN };
#define PEAK_AT 65            /* where the rise turns, percent of the list */
#define ENERGY_STEP 1000      /* an energy off by 0.1 weighs as much as a typical distance */
#define LINK_WEIGHT 2         /* a link from perfect to none weighs like 2 energy steps */
#define CURVE_STARTS 20       /* a random start among the songs nearest the curve's start */
#define CURVE_PAIRS 200       /* random pairs measured for the typical distance */

/** The energy (percentile x 10000) the curve aims at, at position `pos` of a list of `len`. */
static int curve_target(int shape, int pos, int len)
{
    int64_t last = len > 1 ? len - 1 : 1;
    switch (shape) {
    case CURVE_RISE_FALL:
        if (100 * pos < PEAK_AT * last)
            return 1500 + (int)(8000LL * 100 * pos / (PEAK_AT * last));
        return 9500 - (int)(8000LL * (100 * pos - PEAK_AT * last) / ((100 - PEAK_AT) * last));
    case CURVE_WARM_UP:
        return 1000 + (int)(8500LL * pos / last);
    default:
        return 9000 - (int)(8500LL * pos / last);
    }
}

/**
 * `cfg.count` songs whose energy follows `shape` (calm to lively and back, rising, or calming
 * down), each also near the one before in likeness and in how its end leads into the next start,
 * no artist again within the spacing unless nothing else is left. Starts at `start_rec` (also when
 * the filters leave it out) or, with -1, at random among the songs nearest the curve's start.
 */
static void energy_curve(int shape, int start_rec)
{
    static const char *const names[] = { "rise_and_fall", "warming_up", "winding_down" };
    size_t mark = pbuf.used;
    int sp = spacing();
    struct cand *c = alloc((H.n + 1) * sizeof(*c));
    if (!c) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return;
    }
    int n = scan_by(c, start_rec, NULL, NULL, false);
    if (n < 0) goto cancelled;
    if (start_rec >= 0) {
        c[n].rec = start_rec;
        c[n].key = 0;
        n++;
    }
    if (n == 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        goto out;
    }
    int16_t *vec;
    uint16_t *en;
    struct song *s = load_songs(c, n, &vec, &en);
    if (!s) goto out;
    int total = n < cfg.count ? n : cfg.count;
    uint8_t *used = alloc(n);
    int *path = alloc(total * sizeof(*path));
    struct song *list = alloc(total * sizeof(*list));
    if (!used || !path || !list || !artist_init()) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    rb->memset(used, 0, n);
    /* Likeness and energy weighed alike: the median distance between random pairs. */
    uint32_t pairs[CURVE_PAIRS];
    for (int k = 0; k < CURVE_PAIRS; k++)
        pairs[k] = distance(vec + (rb->rand() % n) * VEC, vec + (rb->rand() % n) * VEC);
    rb->qsort(pairs, CURVE_PAIRS, sizeof(pairs[0]), cmp_u32_up);
    uint32_t typical = pairs[CURVE_PAIRS / 2] ? pairs[CURVE_PAIRS / 2] : 1;
    int first = -1;
    for (int i = 0; i < n && start_rec >= 0; i++)
        if (s[i].rec == (uint32_t)start_rec)
            first = i;
    if (first < 0) {
        int t0 = curve_target(shape, 0, total), near[CURVE_STARTS], k = 0;
        for (; k < CURVE_STARTS && k < n; k++) {
            int best = -1;
            for (int i = 0; i < n; i++)
                if (!used[i] && (best < 0 || iabs(en[i] - t0) < iabs(en[best] - t0)))
                    best = i;
            near[k] = best;
            used[best] = 1;
        }
        first = near[rb->rand() % k];
        rb->memset(used, 0, n);
    }
    path[0] = first;
    used[first] = 1;
    artist_placed(s[first].artist, 0);
    int len = 1;
    while (len < total) {
        int t = curve_target(shape, len, total);
        const struct song *last = &s[path[len - 1]];
        const int16_t *lv = vec + path[len - 1] * VEC;
        int best = -1;
        uint64_t best_cost = ~(uint64_t)0;
        for (int pass = 0; pass < 2 && best < 0; pass++)
            for (int i = 0; i < n; i++) {
                if (used[i] || (pass == 0 && artist_recent(s[i].artist, len, sp)))
                    continue;
                /* Energy on the curve, likeness, and the link (the cheap parts first). */
                uint64_t cost = (uint64_t)iabs(en[i] - t) * 65536 / ENERGY_STEP;
                if (cost >= best_cost)
                    continue;
                cost += (uint64_t)distance(lv, vec + i * VEC) * 65536 / typical;
                if (cost >= best_cost)
                    continue;
                uint32_t link = transition(last, &s[i]);
                cost += (uint64_t)(65536 - (link < 65536 ? link : 65536)) * LINK_WEIGHT;
                if (cost < best_cost) {
                    best_cost = cost;
                    best = i;
                }
            }
        if (best < 0)
            break;
        used[best] = 1;
        artist_placed(s[best].artist, len);
        path[len++] = best;
        if (!progress(T(S_CHAINING), len, total))
            goto cancelled;
    }
    for (int k = 0; k < len; k++)
        list[k] = s[path[k]];
    output(list, len, names[shape], mean_link(list, len));
    goto out;
cancelled:
    rb->splash(HZ, T(S_CANCELLED));
out:
    release(mark);
}

/** The centre (mean vector) of the records of `c[0, n)`. */
static void centre_of(const struct cand *c, int n, int16_t *centre)
{
    int64_t sum[VEC];
    struct record r;
    rb->memset(sum, 0, sizeof(sum));
    for (int i = 0; i < n; i++)
        if (read_record(c[i].rec, &r))
            for (int v = 0; v < VEC; v++)
                sum[v] += r.vec[v];
    for (int v = 0; v < VEC; v++)
        centre[v] = n ? (int16_t)(sum[v] / n) : 0;
}

#define FAMILIES 256

/**
 * A sampler of the library: in turns over the sound families, the most typical song of each
 * (nearest its family's centre), then the next one of each..., until there are `cfg.count` (at
 * least one per family); all chained from the most typical of them.
 */
static void sampler(void)
{
    size_t mark = pbuf.used;
    struct record r;
    if (!reserve(H.n * sizeof(struct cand) * 2 + FAMILIES * (VEC * 10 + 16) + 256)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return;
    }
    struct cand *c = alloc(H.n * sizeof(*c));
    struct cand *pool = alloc(H.n * sizeof(*pool));
    int64_t *sum = alloc(FAMILIES * VEC * sizeof(*sum));
    int16_t *centre = alloc(FAMILIES * VEC * sizeof(*centre));
    uint32_t *size = alloc(FAMILIES * sizeof(*size));
    uint32_t *at = alloc(FAMILIES * sizeof(*at));
    uint32_t *taken = alloc(FAMILIES * sizeof(*taken));
    if (!c || !pool || !sum || !centre || !size || !at || !taken) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    rb->memset(sum, 0, FAMILIES * VEC * sizeof(*sum));
    rb->memset(size, 0, FAMILIES * sizeof(*size));
    int n = scan_by(c, -1, NULL, NULL, false);
    if (n < 0) goto cancelled;
    if (n == 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        goto out;
    }
    for (int i = 0; i < n; i++) {
        if (!get_record(c[i].rec, &r))
            break;
        size[r.family]++;
        for (int v = 0; v < VEC; v++)
            sum[r.family * VEC + v] += r.vec[v];
    }
    int families = 0;
    for (int f = 0; f < FAMILIES; f++) {
        if (size[f])
            families++;
        for (int v = 0; v < VEC; v++)
            centre[f * VEC + v] = size[f] ? (int16_t)(sum[f * VEC + v] / size[f]) : 0;
    }
    /* Key: family, then nearest the family's centre first (as cmp_cand_key sorts, highest first). */
    for (int i = 0; i < n; i++) {
        if (!get_record(c[i].rec, &r))
            break;
        uint32_t d = distance(centre + r.family * VEC, r.vec);
        c[i].key = ((uint32_t)r.family << 24) | (0xFFFFFFu - (d < 0xFFFFFFu ? d : 0xFFFFFFu));
        if ((i & 255) == 0 && !progress(T(S_READING), i, n))
            goto cancelled;
    }
    rb->qsort(c, n, sizeof(*c), cmp_cand_key);
    for (int i = n - 1; i >= 0; i--)
        at[c[i].key >> 24] = i;          /* where each family's run starts */
    rb->memset(taken, 0, FAMILIES * sizeof(*taken));
    int want = cfg.count > families ? cfg.count : families, m = 0;
    while (m < want && m < n)
        for (int f = 0; f < FAMILIES && m < want; f++)
            if (taken[f] < size[f])
                pool[m++] = c[at[f] + taken[f]++];
    int16_t mid[VEC];
    centre_of(pool, m, mid);
    uint32_t first_rec = pool[0].rec, best = NONE32;
    for (int i = 0; i < m; i++)
        if (read_record(pool[i].rec, &r)) {
            uint32_t d = distance(mid, r.vec);
            if (d < best) {
                best = d;
                first_rec = pool[i].rec;
            }
        }
    struct song *s = load_songs(pool, m, NULL, NULL);
    if (!s) goto out;
    int start = 0;
    for (int i = 0; i < m; i++)
        if (s[i].rec == first_rec)
            start = i;
    uint32_t avg = 0;
    int got = chain(s, m, start, m, spacing(), &avg);
    if (got < 0) goto cancelled;
    output(s, got, "sampler", avg);
    goto out;
cancelled:
    rb->splash(HZ, T(S_CANCELLED));
out:
    release(mark);
}

/** `cfg.count` songs as unlike the seed as the library has (artist cap), chained from the farthest. */
static void opposite(int seed_rec)
{
    size_t mark = pbuf.used;
    struct record seed;
    struct cand *c = alloc(H.n * sizeof(*c));
    if (!c || !read_record(seed_rec, &seed)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    int n = scan_by(c, seed_rec, &seed, NULL, true);
    if (n < 0) goto cancelled;
    if (n == 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        goto out;
    }
    int count = cfg.count, take = MIN(n, count + margin_for(count));
    rb->qsort(c, n, sizeof(*c), cmp_cand_key);
    if (!spread(c, n, take, count, NONE16, NONE16)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    uint32_t first_rec = c[0].rec;
    struct song *s = load_songs(c, take, NULL, NULL);
    if (!s) goto out;
    int start = 0;
    for (int i = 0; i < take; i++)
        if (s[i].rec == first_rec)
            start = i;
    uint32_t avg = 0;
    int got = chain(s, take, start, count, spacing(), &avg);
    if (got < 0) goto cancelled;
    char title[120], name[140];
    rb->snprintf(name, sizeof(name), "opposite_%s", get_str(seed.title, title, sizeof(title)));
    output(s, got, name, avg);
    goto out;
cancelled:
    rb->splash(HZ, T(S_CANCELLED));
out:
    release(mark);
}

#define TIME_SLACK 60          /* seconds the list may be off */
#define DEFAULT_LENGTH 240     /* seconds assumed for a song of unknown length */
#define MAX_TIME_SONGS 2000

/**
 * Songs like the seed (as "From the current song"), chained from it, lasting about `minutes`:
 * one that would go over is passed by, the time left is filled with the best-fitting song not yet
 * in, and if still short one song (not the first) is swapped for a longer one that closes the gap.
 */
static void for_time(int seed_rec, int minutes)
{
    size_t mark = pbuf.used;
    struct record seed, r;
    if (!read_record(seed_rec, &seed)) {
        rb->splash(HZ * 2, T(S_BAD_DB));
        return;
    }
    int32_t target = minutes * 60, total = 0;
    uint64_t known = 0;
    uint32_t with_length = 0;
    for (uint32_t i = 0; i < H.n; i++) {
        if (!get_record(i, &r))
            break;
        if (r.duration) {
            known += r.duration;
            with_length++;
        }
    }
    int32_t average = with_length ? (int32_t)(known / with_length) : DEFAULT_LENGTH;
    if (average < 30)
        average = 30;
    int asked = (int)((int64_t)target * 16 / (average * 10));
    asked = asked < 3 ? 3 : asked > MAX_TIME_SONGS ? MAX_TIME_SONGS : asked;
    struct song *s;
    uint32_t avg = 0;
    int got = seed_list(seed_rec, &seed, asked, &s, &avg);
    if (got <= 0)
        goto out;
    uint8_t *in = alloc(got);
    int *chosen = alloc(got * sizeof(*chosen));
    struct song *list = alloc(got * sizeof(*list));
    if (!in || !chosen || !list) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    rb->memset(in, 0, got);
    for (int i = 0; i < got; i++)
        s[i].key = read_record(s[i].rec, &r) && r.duration ? r.duration : (uint32_t)average;
    int k = 0;
    for (int i = 0; i < got; i++) {
        int32_t d = s[i].key;
        if (total + d > target + TIME_SLACK)
            continue;
        chosen[k++] = i;
        in[i] = 1;
        total += d;
        if (total >= target - TIME_SLACK)
            break;
    }
    int32_t gap = target - total;
    if (gap > TIME_SLACK) {
        int best = -1;
        for (int i = 0; i < got; i++)
            if (!in[i] && (int32_t)s[i].key <= gap + TIME_SLACK
                && (best < 0 || iabs((int32_t)s[i].key - gap) < iabs((int32_t)s[best].key - gap)))
                best = i;
        if (best >= 0) {
            chosen[k++] = best;
            in[best] = 1;
            total += s[best].key;
        }
    }
    int32_t left = target - total;
    if (left > TIME_SLACK)
        for (int a = k - 1; a >= 1; a--) {
            int32_t wanted = (int32_t)s[chosen[a]].key + left;
            int other = -1;
            for (int i = 0; i < got && other < 0; i++)
                if (!in[i] && iabs((int32_t)s[i].key - wanted) <= TIME_SLACK)
                    other = i;
            if (other < 0)
                continue;
            in[chosen[a]] = 0;
            chosen[a] = other;
            in[other] = 1;
            break;
        }
    for (int i = 0; i < k; i++)
        list[i] = s[chosen[i]];
    char title[120], name[140];
    rb->snprintf(name, sizeof(name), "%dmin_%s", minutes, get_str(seed.title, title, sizeof(title)));
    output(list, k, name, mean_link(list, k));
out:
    release(mark);
}

/** A string through a read window (for scanning many in file order). */
static char *get_str_win(struct window *w, uint32_t off, char *buf, size_t size)
{
    buf[0] = '\0';
    if (off == NONE32 || off >= H.strsize || size < 2)
        return buf;
    size_t len = MIN(size - 1, H.strsize - off);
    if (!seq_read(w, H.stroff + off, buf, len))
        return get_str(off, buf, size);
    buf[len] = '\0';
    return buf;
}

/** The hash of a path's folder (all before its last "/"). */
static uint32_t folder_hash(const char *path)
{
    const char *slash = rb->strrchr(path, '/');
    return hash_bytes(path, slash ? slash : path);
}

struct atrack { uint32_t rec, folder, dist; };
struct album { uint32_t start, size, score; };
struct apath { uint32_t rec; char *path; };

static int cmp_atrack(const void *a, const void *b)
{
    const struct atrack *x = a, *y = b;
    if (x->folder != y->folder)
        return x->folder < y->folder ? -1 : 1;
    return x->rec < y->rec ? -1 : x->rec > y->rec;
}

static int cmp_album(const void *a, const void *b)
{
    const struct album *x = a, *y = b;
    if (x->score != y->score)
        return x->score < y->score ? -1 : 1;
    return x->start < y->start ? -1 : x->start > y->start;
}

static int cmp_apath(const void *a, const void *b)
{
    const struct apath *x = a, *y = b;
    return rb->strcmp(x->path, y->path);
}

#define MAX_SEED_SONGS 200

/**
 * As Attacca's ChainLists.albumsLike: the seed's folder is its album; the other albums (folders,
 * of the songs passing the filters) ranked by how close their songs are on average to their
 * nearest song of the seed's album, taken whole until they hold `cfg.count` songs; each album's
 * songs in path order; the albums chained end to start, the same artist's apart.
 */
static void albums_like(int seed_rec)
{
    size_t mark = pbuf.used;
    struct record seed, r;
    char path[MAX_PATH];
    struct window swin;
    if (!read_record(seed_rec, &seed)) {
        rb->splash(HZ * 2, T(S_BAD_DB));
        return;
    }
    uint32_t home = folder_hash(get_str(seed.path, path, sizeof(path)));
    if (!reserve(H.n * (sizeof(struct atrack) + sizeof(struct album)) + MAX_SEED_SONGS * VEC * 2 + WINDOW + 256)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return;
    }
    struct atrack *t = alloc(H.n * sizeof(*t));
    struct album *al = alloc(H.n * sizeof(*al));
    int16_t *sv = alloc(MAX_SEED_SONGS * VEC * sizeof(*sv));
    swin.buf = alloc(WINDOW);
    swin.start = swin.len = 0;
    if (!t || !al || !sv || !swin.buf) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    struct limits l;
    limits_of_cfg(&l);
    int n = 0, ns = 0;
    for (uint32_t i = 0; i < H.n; i++) {
        if (!get_record(i, &r))
            break;
        uint32_t f = folder_hash(get_str_win(&swin, r.path, path, sizeof(path)));
        if (f == home) {
            if (ns < MAX_SEED_SONGS)
                rb->memcpy(sv + VEC * ns++, r.vec, sizeof(r.vec));
        } else if (matches_base(&r, NULL) && within(&r, &l)) {
            t[n].rec = i;
            t[n].folder = f;
            n++;
        }
        if ((i & 255) == 0 && !progress(T(S_READING), i, H.n))
            goto cancelled;
    }
    if (n == 0 || ns == 0) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        goto out;
    }
    for (int i = 0; i < n; i++) {
        if (!get_record(t[i].rec, &r))
            break;
        uint32_t best = NONE32;
        for (int k = 0; k < ns; k++) {
            uint32_t d = distance(sv + VEC * k, r.vec);
            if (d < best)
                best = d;
        }
        t[i].dist = best;
        if ((i & 255) == 0 && !progress(T(S_READING), i, n))
            goto cancelled;
    }
    rb->qsort(t, n, sizeof(*t), cmp_atrack);
    int na = 0;
    for (int i = 0; i < n;) {
        int j = i;
        uint64_t sum = 0;
        while (j < n && t[j].folder == t[i].folder)
            sum += t[j++].dist;
        al[na].start = i;
        al[na].size = j - i;
        al[na].score = (uint32_t)(sum / (j - i));
        na++;
        i = j;
    }
    rb->qsort(al, na, sizeof(*al), cmp_album);
    int albums = 0, songs = 0;
    uint32_t biggest = 0;
    while (albums < na && songs < cfg.count) {
        songs += al[albums].size;
        if (al[albums].size > biggest)
            biggest = al[albums].size;
        albums++;
    }
    if (!reserve(songs * (sizeof(uint32_t) + sizeof(struct song)) + biggest * (sizeof(struct apath) + MAX_PATH)
                 + albums * (sizeof(struct song) + 2 * sizeof(struct edge)) + 256)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    /* Each album's songs in path order, all of them in `recs` album after album. */
    uint32_t *recs = alloc(songs * sizeof(*recs));
    struct apath *ap = alloc(biggest * sizeof(*ap));
    char *names = alloc(biggest * MAX_PATH);
    struct song *a = alloc(albums * sizeof(*a));
    struct edge *e = alloc(albums * 2 * sizeof(*e));
    struct song *list = alloc(songs * sizeof(*list));
    if (!recs || !ap || !names || !a || !e || !list) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    int at = 0;
    for (int g = 0; g < albums; g++) {
        const struct album *b = &al[g];
        for (uint32_t k = 0; k < b->size; k++) {
            ap[k].rec = t[b->start + k].rec;
            ap[k].path = names + k * MAX_PATH;
            if (read_record(ap[k].rec, &r))
                get_str(r.path, ap[k].path, MAX_PATH);
            else
                ap[k].path[0] = '\0';
        }
        rb->qsort(ap, b->size, sizeof(*ap), cmp_apath);
        /* The album as one song for the chain: its first song's start and its last one's end. */
        struct record last;
        a[g].rec = g;
        a[g].key = at;
        a[g].edges = &e[2 * g];
        a[g].flags = 0;
        a[g].artist = NONE16;
        rb->memset(&e[2 * g], 0, 2 * sizeof(*e));
        if (read_record(ap[0].rec, &r)) {
            a[g].artist = r.artist;
            if (r.edge != NONE32 && (r.flags & F_HEAD)
                && read_at(H.edgeoff + r.edge * 2 * sizeof(struct edge), &e[2 * g], sizeof(struct edge)))
                a[g].flags |= F_HEAD;
        }
        if (read_record(ap[b->size - 1].rec, &last) && last.edge != NONE32 && (last.flags & F_TAIL)
            && read_at(H.edgeoff + (last.edge * 2 + 1) * sizeof(struct edge), &e[2 * g + 1], sizeof(struct edge)))
            a[g].flags |= F_TAIL;
        for (uint32_t k = 0; k < b->size; k++)
            recs[at++] = ap[k].rec;
        if (!progress(T(S_READING), g, albums))
            goto cancelled;
    }
    uint32_t avg = 0;
    int got = chain(a, albums, 0, albums, spacing(), &avg);
    if (got < 0) goto cancelled;
    int k = 0;
    for (int g = 0; g < got; g++) {
        uint32_t size = al[a[g].rec].size;
        for (uint32_t j = 0; j < size; j++) {
            list[k].rec = recs[a[g].key + j];
            list[k].key = 0;
            list[k].artist = NONE16;
            list[k].flags = 0;
            list[k].edges = NULL;
            k++;
        }
    }
    char album[120], name[140];
    get_str(seed.album, album, sizeof(album));
    rb->snprintf(name, sizeof(name), "albums_like_%s", album[0] ? album : get_str(seed.title, album, sizeof(album)));
    output(list, k, name, avg);
    goto out;
cancelled:
    rb->splash(HZ, T(S_CANCELLED));
out:
    release(mark);
}

#define BRIDGE_WINDOW 4        /* the next songs along the way tried for the best link */

/**
 * A way from `from_rec` to `to_rec`: `cfg.count` songs with both ends, the ones in between those
 * of smallest detour d(from, i) + d(i, to) - d(from, to) (artist cap; of the filters only "Only
 * included", as Attacca), in order along the way; each next one the best link among the next 4
 * not yet placed whose artist is not among the recent ones; if none is, the first such one among
 * the next 12; if none either, the best link among the next 4.
 */
static void bridge(int from_rec, int to_rec)
{
    size_t mark = pbuf.used;
    struct record ra, rz, r;
    struct cand *c = alloc((H.n + 2) * sizeof(*c));
    if (!c || !read_record(from_rec, &ra) || !read_record(to_rec, &rz)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    uint32_t direct = distance(ra.vec, rz.vec);
    int n = 0;
    for (uint32_t i = 0; i < H.n; i++) {
        if (!get_record(i, &r))
            break;
        if ((int)i != from_rec && (int)i != to_rec && (!cfg.only_inc || (r.flags & F_INCLUDED))) {
            uint32_t da = distance(ra.vec, r.vec), db = distance(r.vec, rz.vec);
            uint32_t detour = da + db > direct ? da + db - direct : 0;
            c[n].rec = i;
            c[n].key = NONE32 - detour;
            n++;
        }
        if ((i & 255) == 0 && !progress(T(S_READING), i, H.n))
            goto cancelled;
    }
    int count = cfg.count, take = MIN(n, count > 2 ? count - 2 : 0);
    rb->qsort(c, n, sizeof(*c), cmp_cand_key);
    if (!spread(c, n, take, count, ra.artist, rz.artist)) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    /* Along the way: how far from the first, as part of both distances (1 + x 65536). */
    for (int i = 0; i < take; i++)
        if (read_record(c[i].rec, &r)) {
            uint32_t da = distance(ra.vec, r.vec), db = distance(r.vec, rz.vec);
            c[i].key = 1 + (uint32_t)(((uint64_t)da << 16) / (da + db ? da + db : 1));
        }
    c[take].rec = from_rec;
    c[take].key = 0;
    c[take + 1].rec = to_rec;
    c[take + 1].key = NONE32;
    int m = take + 2;
    struct song *s = load_songs(c, m, NULL, NULL);
    if (!s) goto out;
    struct song *list = alloc(m * sizeof(*list));
    if (!list || !artist_init()) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        goto out;
    }
    /* `s` along the way: the first song first, the last one last, those waiting in between. */
    rb->qsort(s, m, sizeof(*s), cmp_song_key_up);
    int sp = spacing(), len = 1, waiting = 1, left = m - 2;
    list[0] = s[0];
    artist_placed(s[0].artist, 0);
    while (left > 0) {
        int w = left < BRIDGE_WINDOW ? left : BRIDGE_WINDOW, best = -1, best_any = -1;
        uint32_t sv = 0, sa = 0;
        for (int i = waiting; i < waiting + w; i++) {
            uint32_t sc = transition(&list[len - 1], &s[i]);
            if (best_any < 0 || sc > sa) {
                sa = sc;
                best_any = i;
            }
            if (!artist_recent(s[i].artist, len, sp) && (best < 0 || sc > sv)) {
                sv = sc;
                best = i;
            }
        }
        /* None free of the recent artists: the first one free among a few more, else the best link. */
        for (int i = waiting + w; best < 0 && i < waiting + left && i < waiting + BRIDGE_WINDOW * 3; i++)
            if (!artist_recent(s[i].artist, len, sp))
                best = i;
        if (best < 0)
            best = best_any;
        /* Out of the waiting ones: those before it move up one. */
        struct song next = s[best];
        for (int i = best; i > waiting; i--)
            s[i] = s[i - 1];
        waiting++;
        left--;
        artist_placed(next.artist, len);
        list[len++] = next;
        if (!progress(T(S_CHAINING), len, m))
            goto cancelled;
    }
    list[len++] = s[m - 1];
    char ta[60], tz[60], name[140];
    rb->snprintf(name, sizeof(name), "bridge_%s_to_%s", get_str(ra.title, ta, sizeof(ta)), get_str(rz.title, tz, sizeof(tz)));
    output(list, len, name, mean_link(list, len));
    goto out;
cancelled:
    rb->splash(HZ, T(S_CANCELLED));
out:
    release(mark);
}

/* ======================= Menus ======================= */

static const char *fmt_off(char *buf, size_t size, int value, const char *unit)
{
    if (!value)
        rb->strlcpy(buf, T(S_OFF), size);
    else
        rb->snprintf(buf, size, "%d%s", value, unit ? unit : "");
    return buf;
}

static const char *fmt_pct_max(char *buf, size_t size, int value, const char *unit)
{
    (void)unit;
    if (value >= 100)
        rb->strlcpy(buf, T(S_OFF), size);
    else
        rb->snprintf(buf, size, "%d %%", value);
    return buf;
}

static const char *fmt_pct_min(char *buf, size_t size, int value, const char *unit)
{
    (void)unit;
    if (value <= 0)
        rb->strlcpy(buf, T(S_OFF), size);
    else
        rb->snprintf(buf, size, "%d %%", value);
    return buf;
}

static int pick_dict;
static const char *pick_name(int i, void *data, char *buf, size_t len)
{
    (void)data;
    if (i == 0)
        return T(S_ANY);
    return dict_str(pick_dict, i - 1, buf, len);
}

static void pick_category(int c, int title)
{
    struct simplelist_info info;
    pick_dict = c;
    rb->simplelist_info_init(&info, (char *)T(title), dict_n[c] + 1, NULL);
    info.get_name = pick_name;
    info.selection = cat_idx[c] + 1;
    rb->simplelist_show_list(&info);
    if (info.selection < 0)
        return;
    cat_idx[c] = info.selection - 1;
    if (cat_idx[c] < 0)
        cat_name[c][0] = '\0';
    else
        dict_str(c, cat_idx[c], cat_name[c], NAME_LEN);
}

static const char *(*menu_label)(int i, char *buf, size_t len);
static const char *menu_name(int i, void *data, char *buf, size_t len)
{
    (void)data;
    return menu_label(i, buf, len);
}

static int run_menu(const char *title, int count, int *sel,
                    const char *(*label)(int, char *, size_t))
{
    struct simplelist_info info;
    menu_label = label;
    rb->simplelist_info_init(&info, (char *)title, count, NULL);
    info.get_name = menu_name;
    info.selection = *sel;
    rb->simplelist_show_list(&info);
    if (info.selection >= 0)
        *sel = info.selection;
    return info.selection;
}

static const char *label_value(char *buf, size_t len, int text, const char *value)
{
    rb->snprintf(buf, len, "%s: %s", T(text), value);
    return buf;
}

/* Category menu: mood, family, genre, artist (dictionaries 2, 3, 1, 0). */
static const int cat_order[4] = { D_MOOD, D_FAMILY, D_GENRE, D_ARTIST };
static const int cat_title[4] = { S_MOOD, S_FAMILY, S_GENRE, S_ARTIST };

static const char *category_label(int i, char *buf, size_t len)
{
    if (i == 4)
        return T(S_CLEAR);
    int c = cat_order[i];
    return label_value(buf, len, cat_title[i], cat_name[c][0] ? cat_name[c] : T(S_ANY));
}

static void category_menu(void)
{
    int sel = 0;
    while (run_menu(T(S_CATEGORY), 5, &sel, category_label) >= 0) {
        if (sel < 4)
            pick_category(cat_order[sel], cat_title[sel]);
        else
            for (int c = 0; c < 4; c++) {
                cat_idx[c] = -1;
                cat_name[c][0] = '\0';
            }
    }
}

static const char *more_label(int i, char *buf, size_t len)
{
    char v[24];
    switch (i) {
    case 0: return label_value(buf, len, S_YEAR_FROM, fmt_off(v, sizeof(v), cfg.year_from, ""));
    case 1: return label_value(buf, len, S_YEAR_TO, fmt_off(v, sizeof(v), cfg.year_to, ""));
    case 2: return label_value(buf, len, S_BPM_MIN, fmt_off(v, sizeof(v), cfg.bpm_min, ""));
    case 3: return label_value(buf, len, S_BPM_MAX, fmt_off(v, sizeof(v), cfg.bpm_max, ""));
    case 4: return label_value(buf, len, S_ENERGY_MIN, fmt_pct_min(v, sizeof(v), cfg.e_min, NULL));
    case 5: return label_value(buf, len, S_ENERGY_MAX, fmt_pct_max(v, sizeof(v), cfg.e_max, NULL));
    case 6: return label_value(buf, len, S_POS_MIN, fmt_pct_min(v, sizeof(v), cfg.p_min, NULL));
    case 7: return label_value(buf, len, S_POS_MAX, fmt_pct_max(v, sizeof(v), cfg.p_max, NULL));
    case 8: return label_value(buf, len, S_VOICE,
                               cfg.voice == 1 ? T(S_VOICE_VOICE) : cfg.voice == 2 ? T(S_VOICE_INSTR) : T(S_ANY));
    case 9:
        if (set_n > 0)
            return label_value(buf, len, S_SET, cfg.only_inc && set_bit >= 0 ? incl_set_name : T(S_EVERYTHING));
        return label_value(buf, len, S_ONLY_INC, cfg.only_inc ? T(S_YES) : T(S_NO));
    case 10: return label_value(buf, len, S_MIN_MINUTES, fmt_off(v, sizeof(v), cfg.min_minutes, ""));
    case 11: return label_value(buf, len, S_MAX_MINUTES, fmt_off(v, sizeof(v), cfg.max_minutes, ""));
    default: return T(S_RESET);
    }
}

static const char *set_label(int i, char *buf, size_t len)
{
    if (i == 0)
        return T(S_EVERYTHING);
    return get_str(set_off[i - 1], buf, len);
}

/** Which inclusion set lists keep to: everything, or one of the library's sets. */
static void choose_set(void)
{
    int sel = cfg.only_inc && set_bit >= 0 ? set_bit + 1 : 0;
    if (run_menu(T(S_SET), set_n + 1, &sel, set_label) < 0)
        return;
    if (sel == 0) {
        incl_set_name[0] = '\0';
        cfg.only_inc = 0;
    } else {
        get_str(set_off[sel - 1], incl_set_name, sizeof(incl_set_name));
        cfg.only_inc = 1;
    }
    resolve_set();
}

static void more_menu(void)
{
    int sel = 0;
    while (run_menu(T(S_MORE), 13, &sel, more_label) >= 0) {
        switch (sel) {
        case 0: rb->set_int(T(S_YEAR_FROM), "", UNIT_INT, &cfg.year_from, NULL, 1, 0, 2100, fmt_off); break;
        case 1: rb->set_int(T(S_YEAR_TO), "", UNIT_INT, &cfg.year_to, NULL, 1, 0, 2100, fmt_off); break;
        case 2: rb->set_int(T(S_BPM_MIN), "", UNIT_INT, &cfg.bpm_min, NULL, 5, 0, 400, fmt_off); break;
        case 3: rb->set_int(T(S_BPM_MAX), "", UNIT_INT, &cfg.bpm_max, NULL, 5, 0, 400, fmt_off); break;
        case 4: rb->set_int(T(S_ENERGY_MIN), "", UNIT_INT, &cfg.e_min, NULL, 5, 0, 100, fmt_pct_min); break;
        case 5: rb->set_int(T(S_ENERGY_MAX), "", UNIT_INT, &cfg.e_max, NULL, 5, 0, 100, fmt_pct_max); break;
        case 6: rb->set_int(T(S_POS_MIN), "", UNIT_INT, &cfg.p_min, NULL, 5, 0, 100, fmt_pct_min); break;
        case 7: rb->set_int(T(S_POS_MAX), "", UNIT_INT, &cfg.p_max, NULL, 5, 0, 100, fmt_pct_max); break;
        case 8: cfg.voice = (cfg.voice + 1) % 3; break;
        case 9:
            if (set_n > 0)
                choose_set();
            else
                cfg.only_inc = !cfg.only_inc;
            break;
        case 10: rb->set_int(T(S_MIN_MINUTES), "", UNIT_INT, &cfg.min_minutes, NULL, 1, 0, 600, fmt_off); break;
        case 11: rb->set_int(T(S_MAX_MINUTES), "", UNIT_INT, &cfg.max_minutes, NULL, 1, 0, 600, fmt_off); break;
        default:
            cfg.year_from = cfg.year_to = cfg.bpm_min = cfg.bpm_max = cfg.voice = 0;
            cfg.e_min = cfg.p_min = 0;
            cfg.e_max = cfg.p_max = 100;
            cfg.min_minutes = 1;
            cfg.max_minutes = 20;
            break;
        }
    }
}

static const char *artist_name(int i, void *data, char *buf, size_t len)
{
    (void)data;
    return dict_str(D_ARTIST, i, buf, len);
}

static uint32_t *pick_recs;
static const char *song_name(int i, void *data, char *buf, size_t len)
{
    struct record r;
    (void)data;
    if (!read_record(pick_recs[i], &r)) {
        buf[0] = '\0';
        return buf;
    }
    return get_str(r.title, buf, len);
}

#define MAX_PICK 1000

/** A song chosen by artist and then title, or -1. Its memory is the caller's to release. */
static int pick_song(void)
{
    struct simplelist_info info;
    if (!dict_n[D_ARTIST])
        return -1;
    rb->simplelist_info_init(&info, (char *)T(S_PICK_ARTIST), dict_n[D_ARTIST], NULL);
    info.get_name = artist_name;
    rb->simplelist_show_list(&info);
    if (info.selection < 0)
        return -1;
    uint32_t artist = info.selection;
    pick_recs = alloc(MAX_PICK * sizeof(*pick_recs));
    if (!pick_recs) {
        rb->splash(HZ * 2, T(S_NO_MEMORY));
        return -1;
    }
    struct record r;
    int n = 0;
    for (uint32_t i = 0; i < H.n && n < MAX_PICK; i++) {
        if (!get_record(i, &r))
            break;
        if (r.artist == artist)
            pick_recs[n++] = i;
    }
    if (!n) {
        rb->splash(HZ * 2, T(S_NO_SONGS));
        return -1;
    }
    rb->simplelist_info_init(&info, (char *)T(S_PICK_SONG), n, NULL);
    info.get_name = song_name;
    rb->simplelist_show_list(&info);
    return info.selection < 0 ? -1 : (int)pick_recs[info.selection];
}

#define OTHER_KINDS 10
#define OTHER_ITEMS (OTHER_KINDS + 2)

static const char *other_label(int i, char *buf, size_t len)
{
    static const int kinds[OTHER_KINDS] = { S_RISE_FALL, S_WARM_UP, S_WIND_DOWN, S_SAMPLER, S_OPPOSITE, S_FOR_TIME, S_ALBUMS, S_BRIDGE, S_BRIDGE_TO, S_RANDOM };
    char v[24];
    if (i < OTHER_KINDS)
        return T(kinds[i]);
    if (i == OTHER_KINDS) {
        rb->snprintf(v, sizeof(v), "%d", cfg.minutes);
        return label_value(buf, len, S_MINUTES, v);
    }
    return label_value(buf, len, S_CURVE_START, cfg.curve_current ? T(S_YES) : T(S_NO));
}

static void other_menu(void)
{
    int sel = 0, seed;
    while (!played && run_menu(T(S_OTHER), OTHER_ITEMS, &sel, other_label) >= 0) {
        last_progress = 0;
        switch (sel) {
        case 0: energy_curve(CURVE_RISE_FALL, cfg.curve_current ? playing_record() : -1); break;
        case 1: energy_curve(CURVE_WARM_UP, cfg.curve_current ? playing_record() : -1); break;
        case 2: energy_curve(CURVE_WIND_DOWN, cfg.curve_current ? playing_record() : -1); break;
        case 3: sampler(); break;
        case 4: if ((seed = current_record()) >= 0) opposite(seed); break;
        case 5: if ((seed = current_record()) >= 0) for_time(seed, cfg.minutes); break;
        case 6: if ((seed = current_record()) >= 0) albums_like(seed); break;
        case 7:
        case 8:
            if ((seed = current_record()) >= 0) {
                size_t mark = pbuf.used;
                int other = pick_song();
                if (other >= 0) {
                    if (sel == 7)
                        bridge(seed, other);
                    else
                        bridge(other, seed);
                }
                release(mark);
            }
            break;
        case 9: random_list(); break;
        case 10: rb->set_int(T(S_MINUTES), "", UNIT_INT, &cfg.minutes, NULL, 5, 5, 600, NULL); break;
        default: cfg.curve_current = !cfg.curve_current; break;
        }
    }
}

#define MAIN_ITEMS 20
#define MAX_LIBS 16
static char lib_paths[MAX_LIBS][MAX_PATH];
static int lib_count;

static const char *lib_label(int i, char *buf, size_t len)
{
    (void)buf; (void)len;
    const char *slash = rb->strrchr(lib_paths[i], '/');
    return slash ? slash + 1 : lib_paths[i];
}

/** Adds the .bpcr2 files of [dir] (ending in '/') to the libraries to choose from. */
static void find_libs(const char *dir)
{
#ifdef HOST_TEST
    (void)dir;
#else
    DIR *d = rb->opendir(dir);
    if (!d)
        return;
    struct dirent *e;
    while (lib_count < MAX_LIBS && (e = rb->readdir(d)) != NULL) {
        size_t n = rb->strlen(e->d_name);
        if (n > 6 && !rb->strcasecmp(e->d_name + n - 6, ".bpcr2"))
            rb->snprintf(lib_paths[lib_count++], MAX_PATH, "%s%s", dir, e->d_name);
    }
    rb->closedir(d);
#endif
}

static const char *main_label(int i, char *buf, size_t len)
{
    char v[24];
    switch (i) {
    case 0: return T(S_FROM_CURRENT);
    case 1: return T(S_BY_FILTERS);
    case 2: return T(S_REORDER);
    case 3: return T(S_OTHER);
    case 4: return T(S_CATEGORY);
    case 5: return T(S_MORE);
    case 6: return T(S_FIT);
    case 7: return label_value(buf, len, S_SAME_MOOD, cfg.same_mood ? T(S_YES) : T(S_NO));
    case 8: return label_value(buf, len, S_SAME_FAMILY, cfg.same_family ? T(S_YES) : T(S_NO));
    case 9: rb->snprintf(v, sizeof(v), "%d", cfg.count); return label_value(buf, len, S_COUNT, v);
    case 10: rb->snprintf(v, sizeof(v), "%d", spacing()); return label_value(buf, len, S_SPACING, v);
    case 11: return label_value(buf, len, S_AFTER, cfg.play ? T(S_PLAY) : T(S_SAVE_ONLY));
    case 12: return label_value(buf, len, S_START_CURRENT, cfg.start_current ? T(S_YES) : T(S_NO));
    case 13: return label_value(buf, len, S_XF, cfg.xf_on ? T(S_YES) : T(S_NO));
    case 14: rb->snprintf(v, sizeof(v), "%d s", cfg.xf_len); return label_value(buf, len, S_XF_LEN, v);
    case 15: return label_value(buf, len, S_XF_JUMP, cfg.xf_jump ? T(S_YES) : T(S_NO));
    case 16: return label_value(buf, len, S_KEEP_ALBUMS, cfg.blend_albums ? T(S_NO) : T(S_YES));
    case 17: {
        const char *slash = rb->strrchr(dbpath, '/');
        return label_value(buf, len, S_LIBRARY, slash ? slash + 1 : dbpath);
    }
    case 18: return label_value(buf, len, S_SKIP_FADE, cfg.skip_fade ? T(S_YES) : T(S_NO));
    default: return T(S_INFO);
    }
}

/** Opens another of the libraries found (the plugin's memory taken back from [mark]). */
static void choose_library(size_t mark)
{
    lib_count = 0;
    find_libs(HOME "/");
    find_libs(ROCKBOX_DIR "/");
    if (lib_count == 0) {
        rb->splash(HZ * 2, T(S_NO_DB));
        return;
    }
    int sel = 0;
    for (int i = 0; i < lib_count; i++)
        if (!rb->strcmp(lib_paths[i], dbpath))
            sel = i;
    if (run_menu(T(S_LIBRARY), lib_count, &sel, lib_label) < 0)
        return;
    static char was[MAX_PATH];
    rb->strlcpy(was, dbpath, sizeof(was));
    pbuf.used = mark;
    hpath = htail = NULL;
    const char *err = open_db(lib_paths[sel]);
    if (err) {
        rb->splash(HZ * 2, err);
        pbuf.used = mark;
        open_db(was);
    }
    resolve_filters();
}

static void info(void)
{
    char created[40];
    get_str(H.created, created, sizeof(created));
    rb->splashf(HZ * 4, T(S_INFO_FMT), (unsigned long)H.n, created);
}

/* ======================= Entry point ======================= */

enum plugin_status plugin_start(const void *parameter)
{
    pbuf.base = rb->plugin_get_buffer(&pbuf.size);
    pbuf.used = 0;
    abuf.base = NULL;
    audio_taken = played = false;
    hpath = htail = NULL;
    texts = texts_for((const char *)rb->global_settings->lang_file);

    configfile_load(CFG_FILE, config, N_CONFIG, CFG_VERSION);
    if (cfg.count < 1)
        cfg.count = 50;
    if (cfg.minutes < 1)
        cfg.minutes = 60;

    rwin.buf = palloc(WINDOW);
    ewin.buf = palloc(WINDOW);
    rwin.start = rwin.len = ewin.start = ewin.len = 0;
    const char *err = T(S_NO_DB);
    static const char *const defaults[] = { HOME "/attaccaplg.bpcr2", ROCKBOX_DIR "/attaccaplg.bpcr2" };
    size_t before_db = pbuf.used;
#ifdef XF_RESIDENT
    if (parameter == rb->plugin_tsr) {
        /* Back after another plugin: the automatic crossfade goes on, without the menu. */
        if (xf_wanted() && dbpath[0] && !open_db(dbpath) && xf_go_resident())
            return PLUGIN_OK;
        close_db();
        return PLUGIN_OK;
    }
#endif
    /* Opened on a library file it is that library; opened on a song (Open with… in the file
       browser), the usual library, and that song is the seed of the lists. */
    const char *opened = (const char *)parameter;
    const char *dot = opened ? rb->strrchr(opened, '.') : NULL;
    bool is_library = dot != NULL;
    for (int i = 0; is_library && i < 7; i++) {
        char c = dot[i];
        if (c >= 'A' && c <= 'Z')
            c += 'a' - 'A';
        is_library = c == ".bpcr2"[i];   /* the terminating '\0' included */
    }
    chosen_song[0] = '\0';
    if (opened && !is_library)
        rb->strlcpy(chosen_song, opened, sizeof(chosen_song));
    if (is_library)
        err = open_db(opened);
    else {
        if (dbpath[0])
            err = open_db(dbpath);
        for (unsigned i = 0; err && i < sizeof(defaults) / sizeof(defaults[0]); i++) {
            pbuf.used = before_db;
            err = open_db(defaults[i]);
        }
    }
    if (err) {
        rb->splash(HZ * 3, err);
        return PLUGIN_OK;
    }
    resolve_filters();
    rb->srand(*rb->current_tick);
    if (chosen_song[0]) {
        /* Which song the lists start from (its title, else its file name). */
        int rec = load_hashes() ? find_path(chosen_song) : -1;
        struct record r;
        char title[120];
        if (rec >= 0 && read_record(rec, &r))
            rb->splash(HZ * 2, get_str(r.title, title, sizeof(title)));
        else
            rb->splash(HZ * 2, T(S_NOT_IN_DB));
    }

    int sel = 0;
    while (!played && run_menu(T(S_TITLE), MAIN_ITEMS, &sel, main_label) >= 0) {
#ifdef HAVE_ADJUSTABLE_CPU_FREQ
        rb->cpu_boost(true);
#endif
        last_progress = 0;
        switch (sel) {
        case 0: from_current(); break;
        case 1: by_filters(); break;
        case 2: reorder(); break;
        case 3: other_menu(); break;
        case 4: category_menu(); break;
        case 5: more_menu(); break;
        case 6: fit_to_current(); break;
        case 7: cfg.same_mood = !cfg.same_mood; break;
        case 8: cfg.same_family = !cfg.same_family; break;
        case 9: rb->set_int(T(S_COUNT), "", UNIT_INT, &cfg.count, NULL, 10, 10, 1000, NULL); break;
        case 10: {
            int v = spacing();
            rb->set_int(T(S_SPACING), "", UNIT_INT, &v, NULL, 1, 0, 50, NULL);
            cfg.spacing = v;
            break;
        }
        case 11: cfg.play = !cfg.play; break;
        case 12: cfg.start_current = !cfg.start_current; break;
        case 13:
            cfg.xf_on = !cfg.xf_on;
#ifdef XF_RESIDENT
            if (cfg.xf_on && !has_ends()) {
                rb->splash(HZ * 3, T(S_XF_OLD_DB));
                cfg.xf_on = 0;
            } else if (!cfg.xf_on) {
                xf_give_back();
                rb->splash(HZ * 2, T(S_XF_OFF));
            } else {
                rb->splash(HZ * 2, T(S_XF_ON));
            }
#endif
            break;
        case 14: rb->set_int(T(S_XF_LEN), "s", UNIT_INT, &cfg.xf_len, NULL, 1, 1, XF_MAX_DURATION, NULL); break;
        case 15:
            cfg.xf_jump = !cfg.xf_jump;
#ifdef XF_RESIDENT
            /* Back to the user's crossfade mode when it stops jumping (set again on leaving if on). */
            if (!cfg.xf_jump && cfg.xf_saved) {
                rb->global_settings->crossfade = cfg.xf_user[5] == CROSSFADE_ENABLE_OFF ? CROSSFADE_ENABLE_AUTOSKIP : cfg.xf_user[5];
                cfg.xf_mode_set = rb->global_settings->crossfade + 1;
                xf_apply();
            }
#endif
            break;
        case 16:
            cfg.blend_albums = !cfg.blend_albums;
#ifdef XF_RESIDENT
            /* Nothing left for the helper: the user's own crossfade back. */
            if (!xf_wanted() && cfg.xf_saved)
                xf_give_back();
#endif
            break;
        case 17: choose_library(before_db); break;
        case 18:
            cfg.skip_fade = !cfg.skip_fade;
#ifdef XF_RESIDENT
            /* Rockbox's mode follows when the plugin goes on in the background. */
            if (cfg.xf_saved) {
                rb->global_settings->crossfade = xf_base_mode();
                cfg.xf_mode_set = rb->global_settings->crossfade + 1;
                xf_apply();
            }
#endif
            break;
        default: info(); break;
        }
#ifdef HAVE_ADJUSTABLE_CPU_FREQ
        rb->cpu_boost(false);
#endif
        configfile_save(CFG_FILE, config, N_CONFIG, CFG_VERSION);
    }
#ifdef XF_RESIDENT
    /* The automatic crossfade (or keeping albums whole) stays in the background, with the library open. */
    if (xf_wanted() && !xf_go_resident())
        cfg.xf_on = 0;
    configfile_save(CFG_FILE, config, N_CONFIG, CFG_VERSION);
    if (xf.active)
        return played ? PLUGIN_GOTO_WPS : PLUGIN_OK;
#endif
    close_db();
    return played ? PLUGIN_GOTO_WPS : PLUGIN_OK;
}
