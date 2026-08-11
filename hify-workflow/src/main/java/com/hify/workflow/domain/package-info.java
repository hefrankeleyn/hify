/**
 * 工作流模块的领域对象——<b>不落库</b>。
 * <p>只有本模块内部使用的运行期结构，如一次执行的上下文、节点间传递的变量表。
 * <p>与 {@code entity} 的区别：不对应任何数据库表。
 * <p>与 {@code dto} 的区别：不跨模块传递，不出现在 {@code service} 接口签名里。
 */
package com.hify.workflow.domain;
