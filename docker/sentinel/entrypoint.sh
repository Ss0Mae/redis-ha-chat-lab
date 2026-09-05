#!/bin/sh
# Sentinel 은 자기 설정 파일을 다시 쓰므로 템플릿을 복사해서 실행한다.
set -e
# 첫 기동에만 템플릿에서 생성. 재시작 시에는 Sentinel 이 기록한 상태(현재 master, 알려진 replica·sentinel, epoch)를 유지한다.
[ -f /data/sentinel.conf ] && exec redis-sentinel /data/sentinel.conf
sed -e "s/__DOWN_AFTER__/${DOWN_AFTER:-5000}/" -e "s/__FAILOVER_TIMEOUT__/${FAILOVER_TIMEOUT:-20000}/" -e "s/__REBOOT_DOWN__/${REBOOT_DOWN:-0}/" \
  /conf/sentinel.conf.template > /data/sentinel.conf
exec redis-sentinel /data/sentinel.conf
