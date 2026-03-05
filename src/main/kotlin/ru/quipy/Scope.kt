package ru.quipy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import org.springframework.stereotype.Component

@Component
class Scope {
    @OptIn(ExperimentalCoroutinesApi::class)
    val esServiceCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
}