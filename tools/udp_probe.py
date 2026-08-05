#!/usr/bin/env python3
"""低负载 UDP 双端诊断工具喵~"""

import argparse
import json
import socket
import statistics
import struct
import sys
import time

MAGIC = b"GEYSERUDP1"
HEADER = struct.Struct("!10sBQdI")
MAX_PACKET_SIZE = 1400


def build_packet(sequence: int, payload_size: int) -> bytes:
    """构造包含序号和发送时间的探测包喵~"""
    # 喵~防御：限制数据包大小，避免意外产生大流量或超过常见 MTU 喵~
    if payload_size < HEADER.size or payload_size > MAX_PACKET_SIZE:
        raise ValueError(f"payload-size 必须在 {HEADER.size} 到 {MAX_PACKET_SIZE} 之间喵~")
    # 使用固定头部记录协议标识、序号、发送时间和总长度喵~
    packet = HEADER.pack(MAGIC, 0, sequence, time.time(), payload_size)
    # 用固定字节填充剩余空间，避免每个包产生复杂对象喵~
    return packet + bytes(payload_size - HEADER.size)


def parse_packet(packet: bytes):
    """解析探测包并拒绝格式异常的数据喵~"""
    # 喵~防御：丢弃过短或过大的数据包，避免解包异常和异常内存消耗喵~
    if len(packet) < HEADER.size or len(packet) > MAX_PACKET_SIZE:
        return None
    # 读取固定头部字段并校验协议标识与声明长度喵~
    magic, kind, sequence, sent_at, declared_size = HEADER.unpack(packet[:HEADER.size])
    if magic != MAGIC or kind != 0 or declared_size != len(packet):
        return None
    return sequence, sent_at


def print_stats(sent, received, rtts, out_of_order):
    """输出当前 UDP 统计窗口喵~"""
    # 计算发送、接收和丢包数量喵~
    lost = max(0, sent - received)
    loss_percent = lost * 100.0 / sent if sent else 0.0
    # 计算平均、最大和抖动，空样本回退为零喵~
    average = statistics.mean(rtts) if rtts else 0.0
    maximum = max(rtts) if rtts else 0.0
    jitter = statistics.pstdev(rtts) if len(rtts) > 1 else 0.0
    # 使用 JSON 输出，便于复制到日志或后续脚本分析喵~
    print(json.dumps({
        "sent": sent,
        "received": received,
        "lost": lost,
        "lossPercent": round(loss_percent, 3),
        "rttMsAvg": round(average, 3),
        "rttMsMax": round(maximum, 3),
        "rttMsJitter": round(jitter, 3),
        "outOfOrder": out_of_order,
    }, ensure_ascii=False), flush=True)


def run_server(arguments):
    """运行 UDP 回显服务端喵~"""
    # 创建 UDP socket 并绑定指定地址喵~
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    # 喵~防御：允许进程重启时快速重新绑定监听端口喵~
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((arguments.bind, arguments.port))
    # 输出监听地址，方便确认 FRP 端口映射喵~
    print(f"UDP probe server listening on {arguments.bind}:{arguments.port}", flush=True)
    # 循环接收并回显合法探测包喵~
    while True:
        packet, address = sock.recvfrom(MAX_PACKET_SIZE)
        if parse_packet(packet) is not None:
            sock.sendto(packet, address)


def run_client(arguments):
    """运行 UDP 探测客户端喵~"""
    # 创建并连接 UDP socket，连接只限制默认目标不建立 TCP 会话喵~
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(arguments.timeout)
    sock.connect((arguments.host, arguments.port))
    # 初始化窗口统计数据喵~
    sent = received = out_of_order = 0
    rtts = []
    last_sequence = -1
    end_time = time.monotonic() + arguments.duration
    # 按固定频率发送小型探测包并等待回显喵~
    while time.monotonic() < end_time:
        sequence = sent
        packet = build_packet(sequence, arguments.payload_size)
        sent_at = time.monotonic()
        sock.send(packet)
        sent += 1
        try:
            response = sock.recv(MAX_PACKET_SIZE)
            parsed = parse_packet(response)
            if parsed is not None:
                response_sequence, _ = parsed
                if response_sequence <= last_sequence:
                    out_of_order += 1
                last_sequence = max(last_sequence, response_sequence)
                received += 1
                rtts.append((time.monotonic() - sent_at) * 1000.0)
        except socket.timeout:
            pass
        # 喵~防御：限制发送间隔为正数，避免零间隔意外形成压测喵~
        time.sleep(1.0 / arguments.rate)
    # 输出最终统计结果喵~
    print_stats(sent, received, rtts, out_of_order)
    sock.close()


def parse_arguments():
    """解析并校验命令行参数喵~"""
    # 创建主命令解析器喵~
    parser = argparse.ArgumentParser(description="低负载 UDP 双端诊断工具喵~")
    subparsers = parser.add_subparsers(dest="mode", required=True)
    # 创建服务端参数喵~
    server = subparsers.add_parser("server")
    server.add_argument("--bind", default="0.0.0.0")
    server.add_argument("--port", type=int, required=True)
    # 创建客户端参数喵~
    client = subparsers.add_parser("client")
    client.add_argument("--host", required=True)
    client.add_argument("--port", type=int, required=True)
    client.add_argument("--duration", type=float, default=60.0)
    client.add_argument("--rate", type=float, default=10.0)
    client.add_argument("--payload-size", type=int, default=256)
    client.add_argument("--timeout", type=float, default=1.0)
    # 解析参数并校验范围喵~
    arguments = parser.parse_args()
    if not 1 <= arguments.port <= 65535:
        parser.error("port 必须在 1 到 65535 之间喵~")
    if arguments.mode == "client" and (arguments.duration <= 0 or arguments.rate <= 0 or arguments.timeout <= 0):
        parser.error("duration、rate 和 timeout 必须大于零喵~")
    return arguments


def main():
    """启动对应模式并处理可预期错误喵~"""
    # 解析命令行参数并启动服务喵~
    arguments = parse_arguments()
    try:
        if arguments.mode == "server":
            run_server(arguments)
        else:
            run_client(arguments)
    except (OSError, ValueError) as error:
        # 喵~防御：用明确错误退出，避免网络或参数异常产生追踪信息喵~
        print(f"UDP probe failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
