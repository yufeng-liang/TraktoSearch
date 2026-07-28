/** 读取 D1 查询的第一行。使用 all() 兼容生产环境的空结果行为。 */
export async function firstRow<T>(statement: D1PreparedStatement): Promise<T | null> {
    const result = await statement.all<T>();
    return result.results[0] ?? null;
}
