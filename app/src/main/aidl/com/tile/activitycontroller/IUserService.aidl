package com.tile.activitycontroller;


interface IUserService {
    void updateTargetPkgNamesMap(in String map) = 0;
    void launchUnexportedActivity(in Intent intent) = 1;
    void exit() = 255;

}