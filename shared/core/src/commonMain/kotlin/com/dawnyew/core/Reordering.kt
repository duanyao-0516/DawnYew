package com.dawnyew.core

/** One complete, versioned permutation. Out-of-scope records keep their original slots and values. */
internal fun <T> reorderEntities(
    all:List<T>,members:List<T>,orderedIds:List<String>,expectedVersion:Long,contentVersion:Long,
    id:(T)->String,update:(T,Int)->T,
):List<T> {
    if(expectedVersion!=contentVersion) conflict("列表已变化，请刷新后重新拖动")
    orderedIds.forEach(::validateId)
    val byId=members.associateBy(id)
    if(orderedIds.size!=members.size || orderedIds.toSet().size!=orderedIds.size || orderedIds.toSet()!=byId.keys) invalid("排序必须包含当前范围的全部项目且不能重复")
    // Build the entire immutable replacement before assigning state: a revision overflow cannot partially commit.
    val replacement=orderedIds.mapIndexed {index,key->update(byId.getValue(key),index)}.iterator()
    return all.map {value->if(id(value) in byId) replacement.next() else value}
}
