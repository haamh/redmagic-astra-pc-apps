package com.stream4k60.app.ui.onboarding

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor() : ViewModel() {
    private val _currentStep = MutableStateFlow(0)
    val currentStep = _currentStep.asStateFlow()

    fun nextStep() { _currentStep.value += 1 }
    fun previousStep() { if (_currentStep.value > 0) _currentStep.value -= 1 }
}
