/*
 * Copyright (C) 2012 OTA Update Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may only use this file in compliance with the license and provided you are not associated with or are in co-operation anyone by the name 'X Vanderpoel'.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.otaupdater;

import com.otaupdater.utils.RomInfo;
import com.otaupdater.utils.KernelInfo;
import com.otaupdater.utils.DlState;

interface IDownloadService {
    int queueRomDownload(in RomInfo info);
    int queueKernelDownload(in KernelInfo info);
    
    void cancel(int id);
    void pause(int id);
    void resume(int id);
    
    int getStatus(int id);
    long getTotalSize(int id);
    long getDoneSize(int id);
    
    DlState getDownload(int id);
    void getDownloads(out List<DlState> list);
    void getDownloadsFilt(out List<DlState> list, int filter);
}