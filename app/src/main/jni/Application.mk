# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

APP_OPTIM := release
APP_PLATFORM := android-29
APP_ABI := armeabi-v7a arm64-v8a
APP_CFLAGS := -O3 -DPKGNAME=io/github/saeeddev94/xray/service
APP_CPPFLAGS := -O3 -std=c++11
NDK_TOOLCHAIN_VERSION := clang
LOCAL_LDFLAGS += -Wl,--build-id=none -Wl,--hash-style=gnu
