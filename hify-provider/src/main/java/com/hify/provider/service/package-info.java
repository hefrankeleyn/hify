/**
 * 模型提供商的对外契约。
 * <p>只放接口，声明本模块能干什么，不含任何实现。
 * <p>🔴 <b>本包就是模块边界本身</b>——模块间没有 api/internal 目录隔离，
 * 其它模块依赖本模块后，只允许看到这里的接口，看不到 {@code service.impl}、{@code mapper}、{@code entity}。
 * <p>🔴 禁止出现：任何实现、Entity、Mapper、{@code Page}/{@code Wrapper}、HTTP 概念（不返回 {@code Result}）。
 * <p>🔴 每个方法必须有 Javadoc，写清 null 语义与抛什么异常——这是跨模块调用方唯一能看到的信息。
 * <p>方法名用固定动词：get / list / page / count / create / update / delete / exists。
 */
package com.hify.provider.service;
