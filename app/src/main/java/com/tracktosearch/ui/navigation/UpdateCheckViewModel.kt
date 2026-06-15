package com.tracktosearch.ui.navigation

import androidx.lifecycle.ViewModel
import com.tracktosearch.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    val updateRepository: UpdateRepository
) : ViewModel()
