/** TMDB 凭据兼容处理。 */
export function buildTmdbAuth(token: string): { headers: HeadersInit; apiKey?: string } {
    const value = token.trim();
    if (value.split('.').length === 3) {
        return { headers: { Authorization: `Bearer ${value}` } };
    }
    return { headers: {}, apiKey: value };
}
